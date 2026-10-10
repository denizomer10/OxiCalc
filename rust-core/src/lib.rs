//! OxiCalc calculation core.
//!
//! Contains the "backend" arithmetic used by the Compose UI: the four basic
//! operations, percent, the scientific functions and the result formatting.
//! Exposed to Kotlin/Android through JNI (`com.oxi.calc.engine.RustEngine`).

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use rust_decimal::prelude::*;
use rust_decimal::Decimal;
use std::str::FromStr;

const SMALL_THRESHOLD: &str = "0.00000001";

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn make_jstring(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn parse(s: &str) -> Option<Decimal> {
    Decimal::from_str(s)
        .or_else(|_| Decimal::from_scientific(s))
        .ok()
}

/// Locale-independent scientific notation, e.g. `1.2345678901e-05`.
fn to_scientific(v: f64) -> String {
    if v == 0.0 || !v.is_finite() {
        return v.to_string();
    }
    let exponent = v.abs().log10().floor() as i32;
    let mantissa = v / 10f64.powi(exponent);
    let rounded = (mantissa * 1e10).round() / 1e10;
    let sign = if exponent < 0 { "-" } else { "+" };
    format!("{}e{}{:02}", rounded, sign, exponent.abs())
}

/// Mirrors the previous Kotlin `formatResult` behaviour.
fn format_decimal(d: Decimal) -> String {
    let n = d.normalize();
    let plain = n.to_string();
    let threshold = Decimal::from_str(SMALL_THRESHOLD).unwrap_or(Decimal::ZERO);
    if plain.len() > 15 || (n.abs() < threshold && !n.is_zero()) {
        to_scientific(n.to_f64().unwrap_or(f64::NAN))
    } else {
        plain
    }
}

fn eval_binary(lhs: &str, rhs: &str, op: &str) -> Option<String> {
    let a = parse(lhs)?;
    let b = parse(rhs)?;
    let result = match op {
        "+" => a.checked_add(b),
        "-" => a.checked_sub(b),
        "\u{00d7}" => a.checked_mul(b), // ×
        "\u{00f7}" => a.checked_div(b), // ÷  (None when b == 0)
        "pow" => {
            let v = a
                .to_f64()
                .unwrap_or(f64::NAN)
                .powf(b.to_f64().unwrap_or(f64::NAN));
            if v.is_finite() {
                Decimal::from_f64_retain(v)
            } else {
                None
            }
        }
        _ => Some(b),
    }?;
    Some(format_decimal(result))
}

fn eval_unary(op: &str, value: &str) -> Option<String> {
    if op == "percent" {
        let a = parse(value)?;
        return a.checked_div(Decimal::from(100i64)).map(format_decimal);
    }

    let v: f64 = value.parse().ok()?;
    let result = match op {
        "sin" => v.to_radians().sin(),
        "cos" => v.to_radians().cos(),
        "tan" => v.to_radians().tan(),
        "log" => {
            if v <= 0.0 {
                f64::NAN
            } else {
                v.log10()
            }
        }
        "ln" => {
            if v <= 0.0 {
                f64::NAN
            } else {
                v.ln()
            }
        }
        "sqrt" => {
            if v < 0.0 {
                f64::NAN
            } else {
                v.sqrt()
            }
        }
        "sq" => v * v,
        "pi" => std::f64::consts::PI,
        "e" => std::f64::consts::E,
        _ => v,
    };

    if !result.is_finite() {
        return None;
    }
    Decimal::from_f64_retain(result).map(format_decimal)
}

#[no_mangle]
pub extern "system" fn Java_com_oxi_calc_engine_RustEngine_binary(
    mut env: JNIEnv,
    _class: JClass,
    lhs: JString,
    rhs: JString,
    op: JString,
) -> jstring {
    let lhs = jstring_to_string(&mut env, &lhs);
    let rhs = jstring_to_string(&mut env, &rhs);
    let op = jstring_to_string(&mut env, &op);
    let out = eval_binary(&lhs, &rhs, &op).unwrap_or_default();
    make_jstring(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_com_oxi_calc_engine_RustEngine_unary(
    mut env: JNIEnv,
    _class: JClass,
    op: JString,
    value: JString,
) -> jstring {
    let op = jstring_to_string(&mut env, &op);
    let value = jstring_to_string(&mut env, &value);
    let out = eval_unary(&op, &value).unwrap_or_default();
    make_jstring(&mut env, out)
}
