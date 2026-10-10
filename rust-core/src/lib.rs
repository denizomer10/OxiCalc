//! OxiCalc calculation core.
//!
//! Contains the "backend" arithmetic used by the Compose UI:
//! the four basic operations, percent, modulo, powers, a full set of
//! scientific functions and the result formatting.
//!
//! Exposed to Kotlin/Android through JNI (`com.oxi.calc.engine.RustEngine`).
//! Every entry point returns an **empty string** when the operation is invalid
//! (division by zero, domain error, overflow, …).

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use rust_decimal::prelude::*;
use rust_decimal::Decimal;
use std::str::FromStr;
use std::sync::OnceLock;

/// Significant digits kept for *approximate* results (scientific functions,
/// roots, powers, non-terminating division). Exact results keep full precision.
/// This is what makes `sin(30°)` print `0.5` instead of `0.49999999999999994`.
const SIG_DIGITS: u32 = 12;
const SMALL_THRESHOLD: &str = "0.00000001";
/// Longest plain (non-scientific) representation we are willing to show.
const MAX_PLAIN_DIGITS: usize = 21;

// --- one-time constants (parsed once instead of on every call) --------------

fn small_threshold() -> Decimal {
    static VALUE: OnceLock<Decimal> = OnceLock::new();
    *VALUE.get_or_init(|| Decimal::from_str(SMALL_THRESHOLD).unwrap_or(Decimal::ZERO))
}

// --- parsing / rounding helpers ---------------------------------------------

fn parse(s: &str) -> Option<Decimal> {
    Decimal::from_str(s)
        .or_else(|_| Decimal::from_scientific(s))
        .ok()
}

/// Rounds a `f64` to `digits` significant figures.
fn round_sf_f64(v: f64, digits: i32) -> f64 {
    if v == 0.0 || !v.is_finite() {
        return v;
    }
    let magnitude = v.abs().log10().floor() as i32;
    let factor = 10f64.powi(digits - 1 - magnitude);
    (v * factor).round() / factor
}

/// Locale-independent, two-digit-exponent scientific string, e.g. `1.23e-05`.
fn sci_from_f64(v: f64) -> String {
    if v == 0.0 || !v.is_finite() {
        return "0".to_string();
    }
    let s = format!("{:e}", v); // shortest round-trip mantissa, e.g. "9.33e157"
    match s.split_once('e') {
        Some((mantissa, exp)) => {
            let (sign, magnitude) = match exp.strip_prefix('-') {
                Some(rest) => ("-", rest),
                None => ("+", exp),
            };
            let magnitude = if magnitude.len() < 2 {
                format!("0{}", magnitude)
            } else {
                magnitude.to_string()
            };
            format!("{}e{}{}", mantissa, sign, magnitude)
        }
        None => s,
    }
}

/// Formats an **exact** decimal: plain for reasonable sizes, scientific for very
/// large or very small magnitudes. No rounding is applied.
fn format_decimal(d: Decimal) -> String {
    let normalized = d.normalize();
    if normalized.is_zero() {
        return "0".to_string();
    }
    let plain = normalized.to_string();
    let absolute = normalized.abs();

    if plain.len() > MAX_PLAIN_DIGITS || (absolute < small_threshold() && !normalized.is_zero()) {
        sci_from_f64(normalized.to_f64().unwrap_or(f64::NAN))
    } else {
        plain
    }
}

/// Formats an **approximate** value, first rounding to [SIG_DIGITS] significant
/// figures to remove floating-point noise. Returns `None` for non-finite values.
fn format_approx(v: f64) -> Option<String> {
    if !v.is_finite() {
        return None;
    }
    if v == 0.0 {
        return Some("0".to_string());
    }
    match Decimal::from_f64_retain(v) {
        Some(d) => Some(format_decimal(d.round_sf(SIG_DIGITS).unwrap_or(d).normalize())),
        // Out of the decimal range: format straight from the float.
        None => Some(sci_from_f64(round_sf_f64(v, SIG_DIGITS as i32))),
    }
}

fn round_to_sig(d: Decimal) -> Decimal {
    d.round_sf(SIG_DIGITS).unwrap_or(d).normalize()
}

// --- factorial --------------------------------------------------------------

fn factorial_decimal(n: u32) -> Option<Decimal> {
    let mut acc = Decimal::ONE;
    for i in 2..=n {
        acc = acc.checked_mul(Decimal::from(i))?;
    }
    Some(acc)
}

fn factorial(value: &str) -> Option<String> {
    let v: f64 = value.parse().ok()?;
    if v < 0.0 || v.fract() != 0.0 || v > 170.0 {
        return None;
    }
    let n = v as u32;
    // Exact while it fits in a decimal; approximate (scientific) beyond that.
    if let Some(d) = factorial_decimal(n) {
        return Some(format_decimal(d));
    }
    let mut acc = 1.0f64;
    for i in 2..=n {
        acc *= i as f64;
    }
    format_approx(acc)
}

// --- operations -------------------------------------------------------------

fn eval_binary(lhs: &str, rhs: &str, op: &str) -> Option<String> {
    match op {
        "pow" => {
            let a: f64 = lhs.parse().ok()?;
            let b: f64 = rhs.parse().ok()?;
            if a < 0.0 && b.fract() != 0.0 {
                return None; // non-integer power of a negative number
            }
            if a == 0.0 && b < 0.0 {
                return None; // 0 to a negative power
            }
            return format_approx(a.powf(b));
        }
        "mod" => {
            let a = parse(lhs)?;
            let b = parse(rhs)?;
            if b.is_zero() {
                return None;
            }
            return a.checked_rem(b).map(format_decimal);
        }
        "÷" => {
            let a = parse(lhs)?;
            let b = parse(rhs)?;
            let q = a.checked_div(b)?;
            // Exact division keeps full precision; a repeating quotient is rounded.
            let exact = q.checked_mul(b).map(|back| back == a).unwrap_or(false);
            return Some(if exact {
                format_decimal(q)
            } else {
                format_decimal(round_to_sig(q))
            });
        }
        _ => {}
    }

    let a = parse(lhs)?;
    let b = parse(rhs)?;
    let result = match op {
        "+" => a.checked_add(b),
        "-" => a.checked_sub(b),
        "\u{00d7}" => a.checked_mul(b), // ×
        _ => Some(b),
    }?;
    Some(format_decimal(result))
}

fn eval_unary(op: &str, value: &str) -> Option<String> {
    match op {
        "percent" => {
            let a = parse(value)?;
            return a.checked_div(Decimal::from(100i64)).map(format_decimal);
        }
        "fact" => return factorial(value),
        _ => {}
    }

    let v: f64 = value.parse().ok()?;
    let result = match op {
        "sin" => v.to_radians().sin(),
        "cos" => v.to_radians().cos(),
        "tan" => {
            let radians = v.to_radians();
            if radians.cos().abs() < 1e-12 {
                return None; // undefined at odd multiples of 90°
            }
            radians.tan()
        }
        "asin" => {
            if !(-1.0..=1.0).contains(&v) {
                return None;
            }
            v.asin().to_degrees()
        }
        "acos" => {
            if !(-1.0..=1.0).contains(&v) {
                return None;
            }
            v.acos().to_degrees()
        }
        "atan" => v.atan().to_degrees(),
        "log" => {
            if v <= 0.0 {
                return None;
            }
            v.log10()
        }
        "ln" => {
            if v <= 0.0 {
                return None;
            }
            v.ln()
        }
        "exp" => v.exp(),
        "exp10" => 10f64.powf(v),
        "sqrt" => {
            if v < 0.0 {
                return None;
            }
            v.sqrt()
        }
        "cbrt" => v.cbrt(),
        "sq" => v * v,
        "recip" => {
            if v == 0.0 {
                return None;
            }
            1.0 / v
        }
        "abs" => v.abs(),
        "pi" => std::f64::consts::PI,
        "e" => std::f64::consts::E,
        _ => v,
    };
    format_approx(result)
}

// --- JNI exports ------------------------------------------------------------

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

// --- tests ------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn b(a: &str, b: &str, op: &str) -> Option<String> {
        eval_binary(a, b, op)
    }
    fn u(op: &str, v: &str) -> Option<String> {
        eval_unary(op, v)
    }

    #[test]
    fn exact_decimal_arithmetic() {
        assert_eq!(b("0.1", "0.2", "+").as_deref(), Some("0.3"));
        assert_eq!(b("0.3", "0.1", "-").as_deref(), Some("0.2"));
        assert_eq!(b("1.1", "1.1", "×").as_deref(), Some("1.21"));
        assert_eq!(b("10", "4", "÷").as_deref(), Some("2.5"));
        assert_eq!(b("111111111", "111111111", "×").as_deref(), Some("12345678987654321"));
    }

    #[test]
    fn repeating_division_is_rounded() {
        assert_eq!(b("1", "3", "÷").as_deref(), Some("0.333333333333"));
        assert_eq!(b("2", "3", "÷").as_deref(), Some("0.666666666667"));
    }

    #[test]
    fn division_and_modulo_by_zero_error() {
        assert_eq!(b("1", "0", "÷"), None);
        assert_eq!(b("0", "0", "÷"), None);
        assert_eq!(b("1", "0", "mod"), None);
    }

    #[test]
    fn trig_is_exact_at_nice_angles() {
        assert_eq!(u("sin", "30").as_deref(), Some("0.5"));
        assert_eq!(u("cos", "60").as_deref(), Some("0.5"));
        assert_eq!(u("sin", "0").as_deref(), Some("0"));
        assert_eq!(u("tan", "45").as_deref(), Some("1"));
        assert_eq!(u("cos", "0").as_deref(), Some("1"));
    }

    #[test]
    fn trig_undefined_angles_error() {
        assert_eq!(u("tan", "90"), None);
        assert_eq!(u("tan", "270"), None);
        assert_eq!(u("tan", "-90"), None);
    }

    #[test]
    fn inverse_trig_returns_degrees() {
        assert_eq!(u("asin", "0.5").as_deref(), Some("30"));
        assert_eq!(u("acos", "0.5").as_deref(), Some("60"));
        assert_eq!(u("atan", "1").as_deref(), Some("45"));
    }

    #[test]
    fn domain_errors() {
        assert_eq!(u("sqrt", "-1"), None);
        assert_eq!(u("log", "0"), None);
        assert_eq!(u("log", "-5"), None);
        assert_eq!(u("ln", "-5"), None);
        assert_eq!(u("asin", "2"), None);
        assert_eq!(u("acos", "-2"), None);
        assert_eq!(u("recip", "0"), None);
    }

    #[test]
    fn roots_and_constants() {
        assert_eq!(u("sqrt", "4").as_deref(), Some("2"));
        assert_eq!(u("sqrt", "2").as_deref(), Some("1.41421356237"));
        assert_eq!(u("cbrt", "27").as_deref(), Some("3"));
        assert_eq!(u("pi", "0").as_deref(), Some("3.14159265359"));
        assert_eq!(u("e", "0").as_deref(), Some("2.71828182846"));
    }

    #[test]
    fn factorial() {
        assert_eq!(u("fact", "0").as_deref(), Some("1"));
        assert_eq!(u("fact", "5").as_deref(), Some("120"));
        assert_eq!(u("fact", "12").as_deref(), Some("479001600"));
        assert_eq!(u("fact", "20").as_deref(), Some("2432902008176640000"));
        assert_eq!(u("fact", "-1"), None);
        assert_eq!(u("fact", "2.5"), None);
    }

    #[test]
    fn powers() {
        assert_eq!(b("2", "10", "pow").as_deref(), Some("1024"));
        assert_eq!(b("2", "0.5", "pow").as_deref(), Some("1.41421356237"));
        assert_eq!(b("9", "0.5", "pow").as_deref(), Some("3"));
        assert_eq!(b("-8", "0.5", "pow"), None);
        assert_eq!(b("0", "-1", "pow"), None);
    }

    #[test]
    fn percent_and_misc() {
        assert_eq!(u("percent", "50").as_deref(), Some("0.5"));
        assert_eq!(u("abs", "-7").as_deref(), Some("7"));
        assert_eq!(u("recip", "4").as_deref(), Some("0.25"));
        assert_eq!(u("exp", "0").as_deref(), Some("1"));
        assert_eq!(u("exp10", "3").as_deref(), Some("1000"));
    }

    #[test]
    fn large_results_cap_or_overflow_cleanly() {
        let big = u("fact", "170").unwrap();
        assert!(big.contains('e'), "expected scientific notation, got {big}");
        assert_eq!(u("exp", "1000"), None);
    }
}
