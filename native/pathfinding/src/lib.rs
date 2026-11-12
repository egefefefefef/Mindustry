mod physics;

use std::collections::VecDeque;

/// Computes the weighted four-way flow field used by Mindustry's enemy pathfinder.
/// `costs` is a row-major array; -1 marks an impassable tile. Unreachable tiles are
/// returned as -1. Targets have a weight of zero, as in the Java implementation.
fn compute(costs: &[i32], width: usize, height: usize, targets: &[i32]) -> Option<Vec<i32>> {
    let len = width.checked_mul(height)?;
    if width == 0 || height == 0 || len != costs.len() {
        return None;
    }

    let mut weights = vec![-1i32; len];
    let mut frontier = VecDeque::new();
    for &target in targets {
        let pos = usize::try_from(target).ok()?;
        if pos < len && weights[pos] != 0 {
            weights[pos] = 0;
            frontier.push_front(pos);
        }
    }

    while let Some(pos) = frontier.pop_back() {
        let base = weights[pos];
        if base < 0 {
            continue;
        }
        let x = pos % width;
        let y = pos / width;

        // Keep the same four-neighbour connectivity as Geometry.d4. Ordering does
        // not affect final weights, but is kept stable for easier parity checks.
        let mut visit = |next: usize| {
            let step = costs[next];
            if step < 0 {
                return;
            }
            let candidate = base.saturating_add(step);
            if weights[next] < 0 || weights[next] > candidate {
                weights[next] = candidate;
                frontier.push_front(next);
            }
        };

        if x > 0 {
            visit(pos - 1);
        }
        if x + 1 < width {
            visit(pos + 1);
        }
        if y > 0 {
            visit(pos - width);
        }
        if y + 1 < height {
            visit(pos + width);
        }
    }

    Some(weights)
}

/// Stable C ABI shared by JNI and the RoboVM/iOS bridge.
/// Returns 1 on success and 0 for invalid input or null pointers.
#[no_mangle]
pub unsafe extern "C" fn mindustry_flowfield_compute(
    costs: *const i32,
    count: i32,
    width: i32,
    height: i32,
    targets: *const i32,
    target_count: i32,
    output: *mut i32,
) -> i32 {
    if count < 0 || target_count < 0 || width <= 0 || height <= 0 || costs.is_null() || output.is_null() {
        return 0;
    }
    if target_count > 0 && targets.is_null() {
        return 0;
    }

    let (Ok(count), Ok(target_count), Ok(width), Ok(height)) = (
        usize::try_from(count),
        usize::try_from(target_count),
        usize::try_from(width),
        usize::try_from(height),
    ) else {
        return 0;
    };
    if width.checked_mul(height) != Some(count) {
        return 0;
    }

    let costs = std::slice::from_raw_parts(costs, count);
    let targets = if target_count == 0 {
        &[]
    } else {
        std::slice::from_raw_parts(targets, target_count)
    };
    let Some(weights) = compute(costs, width, height, targets) else {
        return 0;
    };
    std::ptr::copy_nonoverlapping(weights.as_ptr(), output, count);
    1
}

#[cfg(feature = "jni")]
mod jni_api {
    use super::compute;
    use jni::objects::{JClass, JFloatArray, JIntArray};
    use jni::sys::{jboolean, jfloat, jint, jintArray, JNI_FALSE, JNI_TRUE};
    use jni::JNIEnv;
    use std::ptr;

    fn run(
        mut env: JNIEnv,
        costs: JIntArray,
        width: jint,
        height: jint,
        targets: JIntArray,
    ) -> jintArray {
        let result = (|| {
            let cost_len = env.get_array_length(&costs).ok()? as usize;
            let target_len = env.get_array_length(&targets).ok()? as usize;
            let mut cost_values = vec![0; cost_len];
            let mut target_values = vec![0; target_len];
            env.get_int_array_region(&costs, 0, &mut cost_values).ok()?;
            env.get_int_array_region(&targets, 0, &mut target_values).ok()?;

            let weights = compute(
                &cost_values,
                usize::try_from(width).ok()?,
                usize::try_from(height).ok()?,
                &target_values,
            )?;
            let out = env.new_int_array(weights.len() as jint).ok()?;
            env.set_int_array_region(&out, 0, &weights).ok()?;
            Some(out.into_raw())
        })();

        result.unwrap_or(ptr::null_mut())
    }

    macro_rules! jni_entry {
        ($name:ident) => {
            #[no_mangle]
            pub extern "system" fn $name(
                env: JNIEnv,
                _class: JClass,
                costs: JIntArray,
                width: jint,
                height: jint,
                targets: JIntArray,
            ) -> jintArray {
                run(env, costs, width, height, targets)
            }
        };
    }

    jni_entry!(Java_mindustry_desktop_NativeFlowfieldJNI_computeNative);
    jni_entry!(Java_mindustry_android_NativeFlowfieldJNI_computeNative);

    fn run_physics(
        env: JNIEnv,
        data: JFloatArray,
        local: JIntArray,
        x: jfloat, y: jfloat, width: jfloat, height: jfloat,
    ) -> jboolean {
        let result = (|| {
            let data_len = env.get_array_length(&data).ok()? as usize;
            let local_len = env.get_array_length(&local).ok()? as usize;
            if local_len.checked_mul(4)? != data_len { return None; }
            let mut values = vec![0.0; data_len];
            let mut flags = vec![0; local_len];
            env.get_float_array_region(&data, 0, &mut values).ok()?;
            env.get_int_array_region(&local, 0, &mut flags).ok()?;
            if !super::physics::update(&mut values, &flags, [x, y, width, height]) { return None; }
            env.set_float_array_region(&data, 0, &values).ok()?;
            Some(())
        })();
        if result.is_some() { JNI_TRUE } else { JNI_FALSE }
    }

    macro_rules! jni_physics_entry {
        ($name:ident) => {
            #[no_mangle]
            pub extern "system" fn $name(
                env: JNIEnv, _class: JClass, data: JFloatArray, local: JIntArray,
                x: jfloat, y: jfloat, width: jfloat, height: jfloat,
            ) -> jboolean {
                run_physics(env, data, local, x, y, width, height)
            }
        };
    }

    jni_physics_entry!(Java_mindustry_desktop_NativeFlowfieldJNI_physicsNative);
    jni_physics_entry!(Java_mindustry_android_NativeFlowfieldJNI_physicsNative);
}

#[cfg(test)]
mod tests {
    use super::compute;

    #[test]
    fn computes_weighted_four_way_field_and_keeps_walls_unreachable() {
        // 1 1 1
        // 1 # 1
        // 1 1 1
        let costs = [1, 1, 1, 1, -1, 1, 1, 1, 1];
        let weights = compute(&costs, 3, 3, &[0]).unwrap();
        assert_eq!(weights, [0, 1, 2, 1, -1, 3, 2, 3, 4]);
    }

    #[test]
    fn supports_multiple_targets_and_ignores_out_of_range_targets() {
        let weights = compute(&[1, 5, 1, 1], 2, 2, &[0, 3, 99]).unwrap();
        assert_eq!(weights, [0, 5, 1, 0]);
    }

    #[test]
    fn rejects_bad_dimensions() {
        assert!(compute(&[1, 1], 3, 1, &[0]).is_none());
        assert!(compute(&[], 0, 0, &[]).is_none());
    }
}
