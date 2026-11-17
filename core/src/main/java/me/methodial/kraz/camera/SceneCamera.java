package me.methodial.kraz.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Orbit camera: it looks at {@link #target()} from a direction given by yaw and pitch. Supports both orthographic
 * (the classic guide look, default) and perspective projection.
 */
public final class SceneCamera {
    private final Vector3f target = new Vector3f();
    private float yaw = -35.0f;
    private float pitch = 30.0f;
    private float distance = 16.0f;
    private float fov = 40.0f;
    private float orthoHeight = 8.0f;
    private float radius = 8.0f;
    private boolean orthographic = true;
    private long version;

    public Vector3f target() {
        return target;
    }

    public SceneCamera setTarget(float x, float y, float z) {
        target.set(x, y, z);
        version++;
        return this;
    }

    public SceneCamera setTarget(Vector3f value) {
        return setTarget(value.x, value.y, value.z);
    }

    /** Degrees around the vertical axis. */
    public float yaw() {
        return yaw;
    }

    public SceneCamera setYaw(float degrees) {
        yaw = degrees;
        version++;
        return this;
    }

    /** Degrees above the horizon; positive looks down on the scene. */
    public float pitch() {
        return pitch;
    }

    public SceneCamera setPitch(float degrees) {
        pitch = Math.clamp(degrees, -89.9f, 89.9f);
        version++;
        return this;
    }

    /** Distance from the target in blocks. Only affects framing in perspective mode. */
    public float distance() {
        return distance;
    }

    public SceneCamera setDistance(float blocks) {
        distance = Math.max(0.1f, blocks);
        version++;
        return this;
    }

    /** Vertical field of view in degrees (perspective mode). */
    public float fov() {
        return fov;
    }

    public SceneCamera setFov(float degrees) {
        fov = Math.clamp(degrees, 1.0f, 170.0f);
        version++;
        return this;
    }

    /** Number of blocks visible from top to bottom of the view (orthographic mode). */
    public float orthoHeight() {
        return orthoHeight;
    }

    public SceneCamera setOrthoHeight(float blocks) {
        orthoHeight = Math.max(0.01f, blocks);
        version++;
        return this;
    }

    public boolean orthographic() {
        return orthographic;
    }

    public SceneCamera setOrthographic(boolean value) {
        orthographic = value;
        version++;
        return this;
    }

    /** Multiplies the zoom level; values below 1 zoom in. Works in both projection modes. */
    public SceneCamera zoom(float factor) {
        setDistance(distance * factor);
        setOrthoHeight(orthoHeight * factor);
        return this;
    }

    public SceneCamera orbit(float yawDelta, float pitchDelta) {
        setYaw(yaw + yawDelta);
        setPitch(pitch + pitchDelta);
        return this;
    }

    /** Moves the target along the view plane, in blocks. */
    public SceneCamera pan(float right, float up) {
        Matrix4f view = viewMatrix(new Matrix4f());
        // Rows of the view rotation are the camera's right/up axes in scene space.
        target.add(view.m00() * right + view.m01() * up, view.m10() * right + view.m11() * up, view.m20() * right + view.m21() * up);
        version++;
        return this;
    }

    /** Looks at a sphere so that it fills the view. */
    public SceneCamera frame(Vector3f center, float sphereRadius) {
        radius = Math.max(0.5f, sphereRadius);
        target.set(center);
        orthoHeight = radius * 2.1f;
        distance = radius * 1.05f / (float) Math.sin(Math.toRadians(fov) * 0.5);
        version++;
        return this;
    }

    /** Rough size of the content, used to place the clip planes. Set by {@link #frame}. */
    public SceneCamera setContentRadius(float value) {
        radius = Math.max(0.5f, value);
        version++;
        return this;
    }

    private float eyeDistance() {
        return orthographic ? radius * 4.0f + 64.0f : distance;
    }

    public Vector3f position(Vector3f dest) {
        double yawRad = Math.toRadians(yaw), pitchRad = Math.toRadians(pitch);
        float cosPitch = (float) Math.cos(pitchRad);
        float d = eyeDistance();
        return dest.set(
                target.x + (float) Math.sin(yawRad) * cosPitch * d,
                target.y + (float) Math.sin(pitchRad) * d,
                target.z + (float) Math.cos(yawRad) * cosPitch * d);
    }

    public Matrix4f viewMatrix(Matrix4f dest) {
        Vector3f eye = position(new Vector3f());
        return dest.setLookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, 0, 1, 0);
    }

    /** @param aspect width divided by height */
    public Matrix4f projectionMatrix(float aspect, Matrix4f dest) {
        float d = eyeDistance();
        float depth = radius * 4.0f + 64.0f;
        if (orthographic) {
            float h = orthoHeight * 0.5f;
            return dest.setOrtho(-h * aspect, h * aspect, -h, h, 0.05f, d + depth);
        }
        float near = Math.max(0.05f, d * 0.02f);
        return dest.setPerspective((float) Math.toRadians(fov), aspect, near, d + depth);
    }

    public long version() {
        return version;
    }
}
