//! Experimental one-layer unit physics kernel. Keep the order and strict rectangle
//! tests aligned with Arc's QuadTree.fill/intersect and PhysicsWorld.update.
//! Unsupported inputs return false without changing the caller's buffer.

#[derive(Clone, Copy)]
struct Body {
    x: f32,
    y: f32,
    radius: f32,
    mass: f32,
    local: bool,
    collided: bool,
}

#[derive(Clone, Copy)]
struct Rect {
    x: f32,
    y: f32,
    w: f32,
    h: f32,
}

impl Rect {
    fn overlaps(self, b: Rect) -> bool {
        self.x < b.x + b.w && self.x + self.w > b.x && self.y < b.y + b.h && self.y + self.h > b.y
    }
}

impl Body {
    fn hitbox(self) -> Rect {
        let size = self.radius * 2.0;
        Rect { x: self.x - size / 2.0, y: self.y - size / 2.0, w: size, h: size }
    }
}

struct Node {
    bounds: Rect,
    objects: Vec<usize>,
    // Arc order: bottom-left, bottom-right, top-left, top-right.
    children: Option<Box<[Node; 4]>>,
}

impl Node {
    fn build(bounds: Rect, indices: Vec<usize>, bodies: &[Body]) -> Self {
        if indices.len() <= 5 || bounds.w <= 10.0 || bounds.h <= 10.0 {
            return Self { bounds, objects: indices, children: None };
        }
        let half_w = bounds.w / 2.0;
        let half_h = bounds.h / 2.0;
        let mid_x = bounds.x + half_w;
        let mid_y = bounds.y + half_h;
        let mut parts: [Vec<usize>; 4] = std::array::from_fn(|_| Vec::new());
        let mut objects = Vec::new();
        for i in indices {
            let b = bodies[i].hitbox();
            let top = b.y > mid_y;
            let bottom = b.y < mid_y && b.y + b.h < mid_y;
            let left = b.x < mid_x && b.x + b.w < mid_x;
            let right = !left && b.x > mid_x;
            let child = match (left, right, top, bottom) {
                (true, _, true, _) => Some(2),
                (true, _, _, true) => Some(0),
                (_, true, true, _) => Some(3),
                (_, true, _, true) => Some(1),
                _ => None,
            };
            if let Some(child) = child { parts[child].push(i); } else { objects.push(i); }
        }
        let rects = [
            Rect { x: bounds.x, y: bounds.y, w: half_w, h: half_h },
            Rect { x: bounds.x + half_w, y: bounds.y, w: half_w, h: half_h },
            Rect { x: bounds.x, y: bounds.y + half_h, w: half_w, h: half_h },
            Rect { x: bounds.x + half_w, y: bounds.y + half_h, w: half_w, h: half_h },
        ];
        let mut parts = parts.into_iter();
        let children = std::array::from_fn(|i| Self::build(rects[i], parts.next().unwrap(), bodies));
        Self { bounds, objects, children: Some(Box::new(children)) }
    }

    fn intersect(&self, area: Rect, bodies: &[Body], out: &mut Vec<usize>) {
        if let Some(children) = &self.children {
            // Arc traverses top-left, top-right, bottom-left, bottom-right.
            for i in [2, 3, 0, 1] {
                if children[i].bounds.overlaps(area) { children[i].intersect(area, bodies, out); }
            }
        }
        for &i in &self.objects {
            if bodies[i].hitbox().overlaps(area) { out.push(i); }
        }
    }
}

/// `data` is [x,y,radius,mass] per body; `local` is 0/1. Uses transactional
/// copy-back: Java can safely run its fallback if this returns false.
pub(crate) fn update(data: &mut [f32], local: &[i32], bounds: [f32; 4]) -> bool {
    if data.len() != local.len().saturating_mul(4)
        || bounds.iter().any(|v| !v.is_finite()) || bounds[2] <= 0.0 || bounds[3] <= 0.0 {
        return false;
    }
    let mut bodies = Vec::with_capacity(local.len());
    for (chunk, &is_local) in data.chunks_exact(4).zip(local) {
        if chunk.iter().any(|v| !v.is_finite()) || chunk[2] <= 0.0 || chunk[3] <= 0.0 || !(0..=1).contains(&is_local) {
            return false;
        }
        bodies.push(Body { x: chunk[0], y: chunk[1], radius: chunk[2], mass: chunk[3], local: is_local != 0, collided: false });
    }
    let rect = Rect { x: bounds[0], y: bounds[1], w: bounds[2], h: bounds[3] };
    let tree = Node::build(rect, (0..bodies.len()).collect(), &bodies);
    let mut nearby = Vec::new();
    for i in 0..bodies.len() {
        if !bodies[i].local { continue; }
        nearby.clear();
        tree.intersect(bodies[i].hitbox(), &bodies, &mut nearby);
        for &j in &nearby {
            if j == i || bodies[j].collided { continue; }
            let rs = bodies[i].radius + bodies[j].radius;
            let dx = bodies[i].x - bodies[j].x;
            let dy = bodies[i].y - bodies[j].y;
            let dst2 = dx * dx + dy * dy;
            if dst2 < rs * rs {
                // Java uses a random angle for exactly coincident bodies. Do not
                // change RNG semantics: discard this result and use Java instead.
                if dx == 0.0 && dy == 0.0 { return false; }
                let distance = dst2.sqrt();
                let length = rs - distance;
                let length2 = length * length;
                let scale = if dst2 == length2 { 1.0 } else { (length2 / dst2).sqrt() };
                let vx = dx * scale;
                let vy = dy * scale;
                let mass_sum = bodies[i].mass + bodies[j].mass;
                if !mass_sum.is_finite() || mass_sum <= 0.0 { return false; }
                let m1 = bodies[j].mass / mass_sum;
                let m2 = bodies[i].mass / mass_sum;
                bodies[i].x += vx * m1 / 1.25;
                bodies[i].y += vy * m1 / 1.25;
                if bodies[j].local {
                    bodies[j].x -= vx * m2 / 1.25;
                    bodies[j].y -= vy * m2 / 1.25;
                }
            }
        }
        bodies[i].collided = true;
    }
    if bodies.iter().any(|b| !b.x.is_finite() || !b.y.is_finite()) { return false; }
    for (chunk, body) in data.chunks_exact_mut(4).zip(bodies) {
        chunk[0] = body.x;
        chunk[1] = body.y;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::update;

    #[test]
    fn separates_local_bodies_and_keeps_remote_ones_fixed() {
        let mut data = [10.0, 10.0, 3.0, 1.0, 15.0, 10.0, 3.0, 1.0];
        assert!(update(&mut data, &[1, 0], [0.0, 0.0, 100.0, 100.0]));
        assert!(data[0] < 10.0);
        assert_eq!(data[4], 15.0);
    }

    #[test]
    fn stacked_bodies_trigger_transactional_fallback() {
        let mut data = [5.0, 5.0, 2.0, 1.0, 5.0, 5.0, 2.0, 1.0];
        let before = data;
        assert!(!update(&mut data, &[1, 1], [0.0, 0.0, 100.0, 100.0]));
        assert_eq!(data, before);
    }

    #[test]
    fn rejects_invalid_shapes_without_changes() {
        let mut data = [1.0, 2.0, 3.0, 0.0];
        let before = data;
        assert!(!update(&mut data, &[1], [0.0, 0.0, 100.0, 100.0]));
        assert_eq!(data, before);
        assert!(!update(&mut data, &[], [0.0, 0.0, 100.0, 100.0]));
    }
}
