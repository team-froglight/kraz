package me.methodial.kraz.anim;

/** Standard easing curves mapping linear progress 0..1 to eased progress. */
public enum Easing {
    LINEAR {
        @Override
        public float apply(float t) {
            return t;
        }
    },
    /** Holds the previous value until the keyframe is reached. */
    STEP {
        @Override
        public float apply(float t) {
            return t >= 1.0f ? 1.0f : 0.0f;
        }
    },
    IN_QUAD {
        @Override
        public float apply(float t) {
            return t * t;
        }
    },
    OUT_QUAD {
        @Override
        public float apply(float t) {
            return 1 - (1 - t) * (1 - t);
        }
    },
    IN_OUT_QUAD {
        @Override
        public float apply(float t) {
            return t < 0.5f ? 2 * t * t : 1 - 2 * (1 - t) * (1 - t);
        }
    },
    IN_CUBIC {
        @Override
        public float apply(float t) {
            return t * t * t;
        }
    },
    OUT_CUBIC {
        @Override
        public float apply(float t) {
            float u = 1 - t;
            return 1 - u * u * u;
        }
    },
    IN_OUT_CUBIC {
        @Override
        public float apply(float t) {
            float u = 1 - t;
            return t < 0.5f ? 4 * t * t * t : 1 - 4 * u * u * u;
        }
    },
    IN_SINE {
        @Override
        public float apply(float t) {
            return 1 - (float) Math.cos(t * Math.PI / 2);
        }
    },
    OUT_SINE {
        @Override
        public float apply(float t) {
            return (float) Math.sin(t * Math.PI / 2);
        }
    },
    IN_OUT_SINE {
        @Override
        public float apply(float t) {
            return 0.5f - 0.5f * (float) Math.cos(t * Math.PI);
        }
    },
    IN_EXPO {
        @Override
        public float apply(float t) {
            return t <= 0 ? 0 : (float) Math.pow(2, 10 * t - 10);
        }
    },
    OUT_EXPO {
        @Override
        public float apply(float t) {
            return t >= 1 ? 1 : 1 - (float) Math.pow(2, -10 * t);
        }
    },
    /** Pulls back slightly before moving. */
    IN_BACK {
        @Override
        public float apply(float t) {
            return 2.70158f * t * t * t - 1.70158f * t * t;
        }
    },
    /** Overshoots the target slightly, then settles. */
    OUT_BACK {
        @Override
        public float apply(float t) {
            float u = t - 1;
            return 1 + 2.70158f * u * u * u + 1.70158f * u * u;
        }
    },
    OUT_ELASTIC {
        @Override
        public float apply(float t) {
            if (t <= 0 || t >= 1) {
                return Math.clamp(t, 0f, 1f);
            }
            return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
        }
    },
    OUT_BOUNCE {
        @Override
        public float apply(float t) {
            float n = 7.5625f, d = 2.75f;
            if (t < 1 / d) {
                return n * t * t;
            } else if (t < 2 / d) {
                return n * (t -= 1.5f / d) * t + 0.75f;
            } else if (t < 2.5f / d) {
                return n * (t -= 2.25f / d) * t + 0.9375f;
            }
            return n * (t -= 2.625f / d) * t + 0.984375f;
        }
    };

    public abstract float apply(float t);
}
