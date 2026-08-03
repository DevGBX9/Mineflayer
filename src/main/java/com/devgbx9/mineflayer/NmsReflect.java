package com.devgbx9.mineflayer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Minimal reflection helpers for reaching server internals (NMS).
 *
 * <p>Lookups are resolved by <em>shape</em> - parameter counts and assignable
 * types - instead of by exact signature. Minecraft reorders and adds parameters
 * between drops, so a hard-coded signature that works on 26.1 can disappear on
 * 26.2, and this plugin ships a single jar for both series.
 *
 * <p>Since 26.1 the server is distributed unobfuscated and Spigot, Paper and
 * their forks all use Mojang names, so class and member names are stable enough
 * to look up as plain strings.
 */
public final class NmsReflect {

    private NmsReflect() {
    }

    /** Loads a class, or throws with a message naming what was missing. */
    public static Class<?> clazz(String name) throws ReflectiveOperationException {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new ReflectiveOperationException("missing server class: " + name, e);
        }
    }

    /**
     * Finds a field by name, walking up the hierarchy so inherited private
     * fields are reachable too.
     */
    public static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // Keep walking; the field may be declared on a supertype.
            }
        }
        throw new ReflectiveOperationException(
                "missing field '" + name + "' on " + owner.getName());
    }

    /**
     * Finds the single field of a given type. Used where a name may drift but
     * the type is unambiguous, such as the netty channel held by a connection.
     */
    static Field fieldOfType(Class<?> owner, Class<?> type) throws ReflectiveOperationException {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new ReflectiveOperationException(
                "no field of type " + type.getName() + " on " + owner.getName());
    }

    /**
     * Like {@link #field} but returns {@code null} instead of throwing.
     *
     * <p>For members that are wanted where present and skippable where not, so a
     * field that disappears in a later drop degrades one protection rather than
     * failing the whole plugin.
     */
    static Field fieldOrNull(Class<?> owner, String name) {
        try {
            return field(owner, name);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Like {@link #method} but returns {@code null} instead of throwing. */
    static Method methodOrNull(Class<?> owner, String name, Object... args) {
        try {
            return method(owner, name, args);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Writes a field, ignoring failures so cleanup paths never cascade. A
     * {@code null} field is skipped, which is what makes {@link #fieldOrNull}
     * results usable without a check at every call site.
     */
    static void setQuietly(Object target, Field field, Object value) {
        if (field == null) {
            return;
        }
        try {
            field.set(target, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Best effort only: a type mismatch here must not escape into the
            // repeating task that calls it.
        }
    }

    /** Invokes a method, ignoring failures. A {@code null} method is skipped. */
    static void invokeQuietly(Method method, Object target, Object... args) {
        if (method == null) {
            return;
        }
        try {
            method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Same contract as setQuietly: never escape into the caller's timer.
        }
    }

    /** Finds a no-argument method whose return type is assignable to {@code returns}. */
    static Method getterReturning(Class<?> owner, Class<?> returns)
            throws ReflectiveOperationException {
        for (Method m : owner.getMethods()) {
            if (m.getParameterCount() == 0 && returns.isAssignableFrom(m.getReturnType())) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new ReflectiveOperationException(
                "no zero-arg method returning " + returns.getName() + " on " + owner.getName());
    }

    /**
     * Finds a method by name whose parameters accept {@code args}.
     *
     * <p>Declared methods first, walking up the superclass chain, then the public
     * methods including those inherited from interfaces. The second pass is not
     * redundant: a class that implements an interface without overriding a default
     * method does not declare it, and a lambda or record implementing a protocol
     * interface is exactly that case. Missing it would fail at runtime on a method
     * that is plainly callable.
     */
    public static Method method(Class<?> owner, String name, Object... args)
            throws ReflectiveOperationException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && accepts(m, args)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && accepts(m, args)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new ReflectiveOperationException(
                "no method '" + name + "' on " + owner.getName() + " accepting the given arguments");
    }

    /** Instantiates {@code owner} using whichever constructor accepts {@code args}. */
    static Object construct(Class<?> owner, Object... args) throws ReflectiveOperationException {
        for (Constructor<?> ctor : owner.getDeclaredConstructors()) {
            if (accepts(ctor, args)) {
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
        }
        throw new ReflectiveOperationException(
                "no constructor on " + owner.getName() + " accepting the given arguments");
    }

    /** Reads a public static field, used for enum constants such as GameType.SPECTATOR. */
    public static Object staticField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static boolean accepts(Executable executable, Object[] args) {
        Class<?>[] params = executable.getParameterTypes();
        if (params.length != args.length) {
            return false;
        }
        for (int i = 0; i < params.length; i++) {
            if (!compatible(params[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatible(Class<?> param, Object arg) {
        if (arg == null) {
            return !param.isPrimitive();
        }
        // A boxed argument matches a primitive parameter: reflection unboxes it.
        return box(param).isAssignableFrom(arg.getClass());
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
