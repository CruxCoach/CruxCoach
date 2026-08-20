//! Find a rule in a given chain, then atomically replace it its last expression with a 'log'
//! expression.
//!
//! To use this example, create rules with the example `add-rules`, then run this binary.

use rustables::{
    expr::{ExpressionVariant, Log, RawExpression, Register, Verdict, VerdictType},
    list_chains_for_table, list_rules_for_chain, list_tables, Batch, Chain, MsgType, Rule, Table,
};

const TABLE_NAME: &str = "example-table";
const CHAIN_NAME: &str = "chain-for-incoming-packets";

fn main() -> Result<(), Error> {
    env_logger::init();

    // Find the tables currently inserted on the system
    let get_table = || -> Result<Option<Table>, Error> {
        let tables = list_tables()?;
        for table in tables {
            if let Some(name) = table.get_name() {
                println!("Found table {}", name);

                if name == TABLE_NAME {
                    return Ok(Some(table));
                }
            }
        }

        Ok(None)
    };

    let get_chain = |table: &Table| -> Result<Option<Chain>, Error> {
        let chains = list_chains_for_table(table)?;
        for chain in chains {
            if let Some(name) = chain.get_name() {
                println!("Found chain {}", name);

                if name == CHAIN_NAME {
                    return Ok(Some(chain));
                }
            }
        }

        Ok(None)
    };

    let get_rule = |chain: &Chain| -> Result<Option<(u64, Rule)>, Error> {
        let rules = list_rules_for_chain(&chain)?;
        for mut rule in rules {
            let old_handle = *rule.get_handle().expect("no handle on an existing rule!?");
            println!("Found rule {}", old_handle);

            if let Some(exprs) = rule.get_mut_expressions() {
                let mut found = false;
                // match the rule that contains an Accept verdict
                for expr in exprs.iter_mut() {
                    if let Some(ExpressionVariant::Immediate(imm)) = expr.get_data() {
                        if imm.get_dreg() == Some(&Register::Verdict)
                            && imm.get_data().map(|d| d.get_verdict()).flatten()
                                == Some(&Verdict::default().with_code(VerdictType::Accept))
                        {
                            *expr = RawExpression::from(Log::default());
                            found = true;
                        }
                    }
                }
                if found {
                    let rule = Rule::new(&chain)?.with_expressions(exprs.clone());
                    return Ok(Some((old_handle, rule)));
                }
            }
        }

        Ok(None)
    };

    let table = get_table()?.expect("no table?");
    let chain = get_chain(&table)?.expect("no chain?");
    let (old_rule_handle, new_rule) = get_rule(&chain)?.expect("no rule?");
    println!("Editing rule with handle {}", old_rule_handle);

    // Create a batch. This is used to store all the netlink messages we will later send.
    // Creating a new batch also automatically writes the initial batch begin message needed
    // to tell netlink this is a single transaction that might arrive over multiple netlink packets.
    let mut batch = Batch::new();

    batch.add(
        &Rule::new(&chain)?.with_handle(old_rule_handle),
        MsgType::Del,
    );
    batch.add(&new_rule, MsgType::Add);

    // Finalize the batch and send it. This means the batch end message is written into the batch, telling
    // netfilter the we reached the end of the transaction message. It's also converted to a
    // Vec<u8>, containing the raw netlink data so it can be sent over a netlink socket to netfilter.
    // Finally, the batch is sent over to the kernel.
    Ok(batch.send()?)
}

#[allow(dead_code)]
#[derive(Debug)]
struct Error(String);

impl<T: std::error::Error> From<T> for Error {
    fn from(error: T) -> Self {
        Error(error.to_string())
    }
}
