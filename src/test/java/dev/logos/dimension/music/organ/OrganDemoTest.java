package dev.logos.dimension.music.organ;

import java.io.IOException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** 只在要渲染试听时跑：{@code ./gradlew test --tests "*OrganDemoTest*" -PorganDemo=<输出目录> -PlogosJar=...}。 */
class OrganDemoTest {
    @Test void renderOldAndNewComparison() throws IOException {
        String dir = System.getProperty("logos_dimension.organ_demo", "");
        Assumptions.assumeFalse(dir.isBlank(), "set -PorganDemo=<dir> to render the old/new organ comparison");
        OrganDemo.main(new String[]{dir});
    }
}
