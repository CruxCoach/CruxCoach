//use rustables::{Batch, Chain, ChainMethods, Hook, MatchError, ProtoFamily,
//    Protocol, Rule, RuleMethods, Table, MsgType, Policy};
//use rustables::query::{send_batch, Error as QueryError};
//use rustables::expr::{LogGroup, LogPrefix, LogPrefixError};
use ipnetwork::IpNetwork;
use rustables::error::{BuilderError, QueryError};
use rustables::expr::Log;
use rustables::{
    Batch, Chain, ChainPolicy, Hook, HookClass, MsgType, Protocol, ProtocolFamily, Rule, Table,
};

#[derive(thiserror::Error, Debug)]
pub enum Error {
    #[error("Error building a netlink object")]
    BuildError(#[from] BuilderError),
    #[error("Error applying batch")]
    QueryError(#[from] QueryError),
}

const TABLE_NAME: &str = "main-table";
const INBOUND_CHAIN_NAME: &str = "in-chain";
const FORWARD_CHAIN_NAME: &str = "forward-chain";
const OUTBOUND_CHAIN_NAME: &str = "out-chain";

fn main() -> Result<(), Error> {
    let fw = Firewall::new()?;
    fw.start()?;
    Ok(())
}

/// An example firewall. See the source of its `start()` method.
pub struct Firewall {
    batch: Batch,
    inbound: Chain,
    _outbound: Chain,
    _forward: Chain,
    table: Table,
}

impl Firewall {
    pub fn new() -> Result<Self, Error> {
        let mut batch = Batch::new();
        let table = Table::new(ProtocolFamily::Inet).with_name(TABLE_NAME);
        batch.add(&table, MsgType::Add);

        // Create base chains. Base chains are hooked into a Direction/Hook.
        let inbound = Chain::new(&table)
            .with_name(INBOUND_CHAIN_NAME)
            .with_hook(Hook::new(HookClass::In, 0))
            .with_policy(ChainPolicy::Drop)
            .add_to_batch(&mut batch);
        let _outbound = Chain::new(&table)
            .with_name(OUTBOUND_CHAIN_NAME)
            .with_hook(Hook::new(HookClass::Out, 0))
            .with_policy(ChainPolicy::Accept)
            .add_to_batch(&mut batch);
        let _forward = Chain::new(&table)
            .with_name(FORWARD_CHAIN_NAME)
            .with_hook(Hook::new(HookClass::Forward, 0))
            .with_policy(ChainPolicy::Accept)
            .add_to_batch(&mut batch);

        Ok(Firewall {
            table,
            batch,
            inbound,
            _outbound,
            _forward,
        })
    }
    /// Allow some common-sense exceptions to inbound drop, and accept outbound and forward.
    pub fn start(mut self) -> Result<(), Error> {
        // Allow all established connections to get in.
        Rule::new(&self.inbound)?
            .established()?
            .accept()
            .add_to_batch(&mut self.batch);
        // Allow all traffic on the loopback interface.
        Rule::new(&self.inbound)?
            .iiface("lo")?
            .accept()
            .add_to_batch(&mut self.batch);
        // Allow ssh from anywhere, and log to dmesg with a prefix.
        Rule::new(&self.inbound)?
            .dport(22, Protocol::TCP)
            .accept()
            .with_expr(Log::new(None, Some("allow ssh connection:"))?)
            .add_to_batch(&mut self.batch);

        // Allow http from all IPs in 192.168.1.255/24 .
        let local_net = IpNetwork::new([192, 168, 1, 0].into(), 24).unwrap();
        Rule::new(&self.inbound)?
            .dport(80, Protocol::TCP)
            .snetwork(local_net)?
            .accept()
            .add_to_batch(&mut self.batch);

        // Allow ICMP traffic, drop IGMP.
        Rule::new(&self.inbound)?
            .icmp()
            .accept()
            .add_to_batch(&mut self.batch);
        Rule::new(&self.inbound)?
            .igmp()
            .drop()
            .add_to_batch(&mut self.batch);

        // Log all traffic not accepted to NF_LOG group 1, accessible with ulogd.
        Rule::new(&self.inbound)?
            .with_expr(Log::new(Some(1), None::<String>)?)
            .add_to_batch(&mut self.batch);

        self.batch.send()?;
        println!("table {} commited", TABLE_NAME);
        Ok(())
    }
    /// If there is any table with name TABLE_NAME, remove it.
    pub fn stop(mut self) -> Result<(), Error> {
        self.batch.add(&self.table, MsgType::Add);
        self.batch.add(&self.table, MsgType::Del);

        self.batch.send()?;
        println!("table {} destroyed", TABLE_NAME);
        Ok(())
    }
}
