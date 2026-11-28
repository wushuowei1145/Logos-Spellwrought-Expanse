package dev.logos.dimension;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 着色器里的名字（docs/CINEMATICS.md「着色器」）：N 卡的驱动宽松，A 卡和 Intel 严格，有些写法只在 N 卡上编得过。
 * <ul>
 *   <li>GLSL 的保留字（{@code active}、{@code filter}、{@code input}、{@code output}……）和后续版本的关键字（{@code sample}、{@code buffer}、
 *       {@code shared}……）在任何地方都不能当名字用：连携切入的 {@code float active} 让 A 卡进不去游戏；</li>
 *   <li>不要把内置函数的名字（{@code noise3}、{@code distance}、{@code step}……）声明成自己的变量、参数或函数：{@code noise3} 在 A 卡、Intel 上崩过。</li>
 * </ul>
 */
class ShaderNamesTest {
    static final Set<String> RESERVED = Set.of((
            "common partition active asm class union enum typedef template this packed goto inline noinline volatile public static extern external "
            + "interface long short double half fixed unsigned superp input output hvec2 hvec3 hvec4 dvec2 dvec3 dvec4 fvec2 fvec3 fvec4 "
            + "sampler3DRect filter sizeof cast namespace using row_major resource image1D image2D image3D imageCube iimage1D iimage2D iimage3D "
            + "iimageCube uimage1D uimage2D uimage3D uimageCube image1DArray image2DArray iimage1DArray iimage2DArray uimage1DArray uimage2DArray "
            + "image1DShadow image2DShadow image1DArrayShadow image2DArrayShadow imageBuffer iimageBuffer uimageBuffer "
            + "sample patch subroutine precise buffer shared coherent restrict readonly writeonly atomic_uint").split(" "));
    static final Set<String> BUILTINS = Set.of((
            "radians degrees sin cos tan asin acos atan sinh cosh tanh asinh acosh atanh pow exp log exp2 log2 sqrt inversesqrt abs sign floor "
            + "trunc round roundEven ceil fract mod modf min max clamp mix step smoothstep isnan isinf floatBitsToInt floatBitsToUint "
            + "intBitsToFloat uintBitsToFloat fma frexp ldexp length distance dot cross normalize faceforward reflect refract matrixCompMult "
            + "outerProduct transpose determinant inverse lessThan lessThanEqual greaterThan greaterThanEqual equal notEqual any all not "
            + "bitfieldExtract bitfieldInsert bitfieldReverse bitCount findLSB findMSB textureSize textureQueryLod texture textureProj textureLod "
            + "textureOffset texelFetch texelFetchOffset textureGrad textureGather texture2D texture3D textureCube dFdx dFdy fwidth "
            + "noise1 noise2 noise3 noise4 barrier memoryBarrier imageLoad imageStore imageSize").split(" "));
    static final Pattern IDENT = Pattern.compile("\\b[A-Za-z_]\\w*\\b");
    static final Pattern DECLARED = Pattern.compile("\\b(?:float|int|uint|bool|void|double|[biud]?vec[234]|d?mat[234](?:x[234])?|[iu]?sampler\\w*)"
            + "(?:\\s*\\[\\s*\\d*\\s*])?\\s+(\\w+)");

    @Test void shaderNamesCompileOnStrictDrivers() throws IOException, URISyntaxException {
        // 类加载器拿不到目录，拿一个一定在的着色器，往上两级就是 shaders
        Path root = Path.of(ShaderNamesTest.class.getResource("/assets/logos_dimension/shaders/post/chain_cutin.fsh").toURI()).getParent().getParent();
        List<String> problems = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(p -> p.toString().matches(".*\\.(fsh|vsh|glsl|gsh|csh)$")).toList()) {
                files++;
                String[] lines = strip(Files.readString(f, StandardCharsets.UTF_8)).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].strip().startsWith("#version")) continue;
                    Matcher m = IDENT.matcher(lines[i]);
                    while (m.find()) if (RESERVED.contains(m.group())) problems.add(where(root, f, i) + "保留字 " + m.group());
                    Matcher d = DECLARED.matcher(lines[i]);
                    while (d.find()) if (BUILTINS.contains(d.group(1))) problems.add(where(root, f, i) + "把内置函数名声明成自己的 " + d.group(1));
                }
            }
        }
        assertTrue(files > 10, "找到的着色器太少：" + files);
        assertTrue(problems.isEmpty(), "A 卡、Intel 编不过的名字：\n" + String.join("\n", problems));
    }

    /** 去掉注释（保留行号）。 */
    static String strip(String text) {
        Matcher block = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(text);
        StringBuilder out = new StringBuilder();
        while (block.find()) block.appendReplacement(out, Matcher.quoteReplacement("\n".repeat((int) block.group().chars().filter(c -> c == '\n').count())));
        block.appendTail(out);
        return out.toString().replaceAll("//[^\n]*", "");
    }

    static String where(Path root, Path f, int line) {
        return root.relativize(f) + ":" + (line + 1) + " ";
    }

    @Test void theCheckCatchesWhatBrokeBefore() {
        assertTrue(IDENT.matcher("float active = 1.0;").results().anyMatch(m -> RESERVED.contains(m.group())));
        Matcher d = DECLARED.matcher("float noise3(vec3 p) {");
        assertTrue(d.find() && BUILTINS.contains(d.group(1)));
        assertFalse(IDENT.matcher(strip("// float active\nfloat lit;")).results().anyMatch(m -> RESERVED.contains(m.group())), "注释里的不算");
        assertFalse(IDENT.matcher("flat in float SceneLum;").results().anyMatch(m -> RESERVED.contains(m.group())), "插值限定词照常用");
    }
}
