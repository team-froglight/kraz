package me.methodial.kraz.scene.element;

import com.mojang.blaze3d.vertex.PoseStack;
import me.methodial.kraz.anim.Track;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Base for elements with their own name, position, rotation and scale inside the part. */
public abstract class TransformedElement<S extends TransformedElement<S>> implements SceneElement {
    protected final Vector3f position = new Vector3f();
    protected final Quaternionf rotation = new Quaternionf();
    protected float scale = 1.0f;
    private String name = "";
    private long version;

    @Override
    public long version() {
        return version;
    }

    /** Call from setters of subclasses so cached renders of the scene are refreshed. */
    protected final void touch() {
        version++;
    }

    @SuppressWarnings("unchecked")
    private S self() {
        return (S) this;
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Sets the name timeline tracks address this element by. Left empty, a unique one is assigned when the
     * element is added to a part.
     */
    public S setName(String name) {
        this.name = name;
        return self();
    }

    /** Short lowercase word describing the element, used to build default names such as {@code cow_2}. */
    public abstract String kind();

    public Vector3f position() {
        return position;
    }

    public S setPosition(float x, float y, float z) {
        position.set(x, y, z);
        touch();
        return self();
    }

    public S setPosition(Vector3f value) {
        position.set(value);
        touch();
        return self();
    }

    public Quaternionf rotation() {
        return rotation;
    }

    public S setRotation(Quaternionf value) {
        rotation.set(value);
        touch();
        return self();
    }

    /** Rotation from Euler angles in degrees, applied in Y-X-Z order. */
    public S setRotation(float xDegrees, float yDegrees, float zDegrees) {
        rotation.rotationYXZ((float) Math.toRadians(yDegrees), (float) Math.toRadians(xDegrees), (float) Math.toRadians(zDegrees));
        touch();
        return self();
    }

    public float scale() {
        return scale;
    }

    public S setScale(float value) {
        scale = value;
        touch();
        return self();
    }

    /** Rough radius in blocks around {@link #position()}, used for picking in the editor. */
    public float pickRadius() {
        return 0.5f * scale;
    }

    /** Offset from {@link #position()} to the visual centre, in part space. */
    public Vector3f pickCenter(Vector3f dest) {
        return dest.set(position);
    }

    @Override
    public List<ElementProperty<?>> properties() {
        List<ElementProperty<?>> list = new ArrayList<>();
        list.add(new ElementProperty<>("position", Track.Type.VEC3, () -> new Vector3f(position), this::setPosition));
        list.add(new ElementProperty<>("rotation", Track.Type.QUAT, () -> new Quaternionf(rotation), this::setRotation));
        list.add(new ElementProperty<Float>("scale", Track.Type.FLOAT, () -> scale, this::setScale));
        return list;
    }

    protected void applyTransform(PoseStack pose) {
        pose.translate(position.x, position.y, position.z);
        pose.mulPose(rotation);
        pose.scale(scale, scale, scale);
    }

    /** Packed light at the element's position, taken from the part's lighting. */
    protected int light(Context context) {
        return LevelRenderer.getLightColor(context.part().store(), BlockPos.containing(position.x, position.y, position.z));
    }

    protected void saveTransform(CompoundTag tag) {
        tag.putString("name", name);
        tag.putFloat("x", position.x);
        tag.putFloat("y", position.y);
        tag.putFloat("z", position.z);
        tag.putFloat("qx", rotation.x);
        tag.putFloat("qy", rotation.y);
        tag.putFloat("qz", rotation.z);
        tag.putFloat("qw", rotation.w);
        tag.putFloat("scale", scale);
    }

    protected void loadTransform(CompoundTag tag) {
        name = tag.getString("name");
        position.set(tag.getFloat("x"), tag.getFloat("y"), tag.getFloat("z"));
        if (tag.contains("qw")) {
            rotation.set(tag.getFloat("qx"), tag.getFloat("qy"), tag.getFloat("qz"), tag.getFloat("qw"));
        }
        scale = tag.contains("scale") ? tag.getFloat("scale") : 1.0f;
    }
}
