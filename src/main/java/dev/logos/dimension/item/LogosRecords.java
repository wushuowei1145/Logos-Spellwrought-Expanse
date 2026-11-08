package dev.logos.dimension.item;

/** 反射造 Logos 的记录类：按字段名填，不按位置，Logos 加字段也不会崩。 */
public final class LogosRecords {
    private LogosRecords() {}

    /**
     * 按记录的字段名造一个 Logos 的记录（比如术式 {@code Spell}）。Logos 会给 {@code Spell} 加字段（2026-11-08 的构建多了 element，
     * 按位置传 10 个参数就在第一支箭飞行时崩了），所以走规范构造器、按名字填，没给的字段填类型的默认值
     * （数字 0、布尔 false、其余 null）。
     */
    public static Object make(Class<?> type, java.util.Map<String, Object> values) throws ReflectiveOperationException {
        var components = type.getRecordComponents();
        var types = new Class<?>[components.length];
        var args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            Object value = values.get(components[i].getName());
            // 基本类型的默认值从长度 1 的数组里取
            args[i] = value != null ? value : types[i].isPrimitive() ? java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(types[i], 1), 0) : null;
        }
        return type.getConstructor(types).newInstance(args);
    }
}
