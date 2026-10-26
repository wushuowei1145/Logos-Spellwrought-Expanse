package dev.logos.dimension.item;

/** 原版普通、药水及光灵箭共享的同步标记，不替换它们的实体类型和药效。 */
public interface NatureArrow {
    int natureStage();
    void natureStage(int stage);
    void natureDamageScale(double scale);
}
