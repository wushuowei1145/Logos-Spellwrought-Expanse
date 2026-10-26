package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 每个方块/物品都必须有中英文名字。
 *
 * <p>灵感来自实机反馈："蘑菇的名字显示有问题"。那次查下来蘑菇本身是有名字的，
 * 但顺带发现 `mercury` 与 `molten_glass` 两个方块从来没有翻译——游戏里会直接把
 * 翻译键（`block.logos_dimension.mercury`）显示出来。这个测试把这一类问题钉住。
 *
 * <p>测试类路径里的资源可能是目录（build/resources/main）也可能被打进 jar，
 * 所以枚举注册 ID 时两种形式都要处理。
 */
class LangCoverageTest {
    private static final String ROOT="assets/logos_dimension/";

    private JsonObject lang(String locale)throws IOException{
        try(var input=LangCoverageTest.class.getResourceAsStream("/"+ROOT+"lang/"+locale+".json")){
            assertNotNull(input,locale+" 语言文件不存在");
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private URL anchor()throws IOException{
        var url=LangCoverageTest.class.getResource("/"+ROOT+"lang/zh_cn.json");
        assertNotNull(url,"测试类路径里没有语言文件");
        return url;
    }

    /** 两种资源形态（目录 / jar）都要能列出 blockstates 与 items 里的 JSON。 */
    private Set<String> registeredIds()throws Exception{
        var ids=new TreeSet<String>();
        var url=anchor();
        if("file".equals(url.getProtocol())){
            Path assetsPath=Path.of(url.toURI()).getParent().getParent();
            for(String sub:new String[]{"blockstates","items"}){
                try(Stream<Path> files=Files.list(assetsPath.resolve(sub))){
                    files.filter(p->p.toString().endsWith(".json"))
                         .forEach(p->ids.add(strip(p.getFileName().toString())));
                }
            }
            return ids;
        }
        var connection=url.openConnection();
        assertTrue(connection instanceof JarURLConnection,"无法识别的资源协议："+url);
        try(JarFile jar=((JarURLConnection)connection).getJarFile()){
            var entries=jar.entries();
            while(entries.hasMoreElements()){
                String name=entries.nextElement().getName();
                if(!name.startsWith(ROOT))continue;
                String rest=name.substring(ROOT.length());
                if(rest.startsWith("blockstates/")||rest.startsWith("items/")){
                    if(rest.endsWith(".json")&&!rest.substring(rest.indexOf('/')+1).contains("/"))
                        ids.add(strip(rest.substring(rest.indexOf('/')+1)));
                }
            }
        }
        return ids;
    }

    private static String strip(String file){
        return file.replaceFirst("\\.json$","");
    }

    /** items/ 下的每个条目都要有 `item.` 名字，blockstates/ 下的每个条目都要有 `block.` 名字。 */
    @Test void everyBlockAndItemHasAName()throws Exception{
        var ids=registeredIds();
        assertTrue(ids.size()>100,"注册项数量异常："+ids.size());
        for(String locale:new String[]{"zh_cn","en_us"}){
            var lang=lang(locale);
            var missingItems=new ArrayList<String>();
            var missingBlocks=new ArrayList<String>();
            for(var folder:folderIds().entrySet()){
                var missing=folder.getKey().equals("items")?missingItems:missingBlocks;
                String prefix=folder.getKey().equals("items")?"item.":"block.";
                for(String id:folder.getValue())
                    if(!lang.has(prefix+"logos_dimension."+id))missing.add(id);
            }
            assertTrue(missingItems.isEmpty(),locale+" 缺少物品名字（item.）："+missingItems);
            assertTrue(missingBlocks.isEmpty(),locale+" 缺少方块名字（block.）："+missingBlocks);
        }
    }

    /** 分别列出 blockstates/ 与 items/ 里的 ID。 */
    private static Map<String,Set<String>> folderIds()throws Exception{
        var cache=IDS;
        if(cache!=null)return cache;
        var map=new LinkedHashMap<String,Set<String>>();
        map.put("blockstates",new TreeSet<>());
        map.put("items",new TreeSet<>());
        var url=LangCoverageTest.class.getResource("/assets/logos_dimension/lang/zh_cn.json");
        assertNotNull(url,"测试类路径里没有语言文件");
        if("file".equals(url.getProtocol())){
            Path assetsPath=Path.of(url.toURI()).getParent().getParent();
            for(String sub:map.keySet()){
                try(Stream<Path> files=Files.list(assetsPath.resolve(sub))){
                    files.filter(p->p.toString().endsWith(".json"))
                         .forEach(p->map.get(sub).add(strip(p.getFileName().toString())));
                }
            }
        }else{
            var connection=url.openConnection();
            assertTrue(connection instanceof JarURLConnection,"无法识别的资源协议："+url);
            try(JarFile jar=((JarURLConnection)connection).getJarFile()){
                var entries=jar.entries();
                while(entries.hasMoreElements()){
                    String name=entries.nextElement().getName();
                    if(!name.startsWith(ROOT)||!name.endsWith(".json"))continue;
                    String rest=name.substring(ROOT.length());
                    for(String sub:map.keySet()){
                        if(rest.startsWith(sub+"/")&&!rest.substring(sub.length()+1).contains("/"))
                            map.get(sub).add(strip(rest.substring(sub.length()+1)));
                    }
                }
            }
        }
        IDS=map;
        return map;
    }

    private static Map<String,Set<String>> IDS;

    /** 名字不能是空串，也不能原样回退成翻译键。 */
    @Test void namesAreNotBlank()throws Exception{
        for(String locale:new String[]{"zh_cn","en_us"}){
            for(var entry:lang(locale).entrySet()){
                if(!entry.getKey().startsWith("block.logos_dimension.")
                    &&!entry.getKey().startsWith("item.logos_dimension."))continue;
                String value=entry.getValue().getAsString();
                assertFalse(value.isBlank(),locale+" 里 "+entry.getKey()+" 是空名字");
                assertNotEquals(entry.getKey(),value,locale+" 里 "+entry.getKey()+" 回退成了翻译键");
            }
        }
    }
}
