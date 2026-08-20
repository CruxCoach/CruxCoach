precommit:
	cargo fmt --all -- --config format_code_in_doc_comments=true
	cargo check
	cargo clippy
	cargo test
	cargo check --target wasm32-unknown-unknown
	cargo clippy --target wasm32-unknown-unknown
	wasm-pack test --firefox --headless

test-wasm-firefox:
	wasm-pack test --firefox --headless --test wasm_timeout

clean:
	cargo clean

loc:
	@echo "--- Counting lines of .rs files (LOC):" && find src/ -type f -name "*.rs" -exec cat {} \; | wc -l
