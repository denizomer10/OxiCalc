package com.oxi.calc.engine

/**
 * JNI bridge to the Rust calculation core (`rust-core`, crate `oxicalc_core`).
 * Native symbols are exported from `rust-core/src/lib.rs` and return an
 * **empty string** when the operation is invalid (division by zero, domain error, …).
 */
object RustEngine {
    init {
        System.loadLibrary("oxicalc_core")
    }

    external fun binary(lhs: String, rhs: String, op: String): String
    external fun unary(op: String, value: String): String
}
