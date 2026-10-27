package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 物品拿在手上/掉在地上不能"比正常大一圈"。
 *
 * <p>实机反馈：浅色精灵木板与叶辉嵌片的手持模型和凋落物太大。原因是它们的方块模型没有
 * `parent`（也没写 `display`），物品形态拿不到原版的 display 变换，于是整块 16×16 直接
 * 糊在手上和地上。
 *
 * <p>这个测试检查：只要一个物品引用了 `block/` 模型，那个模型必须带 `parent` 或 `display`。
 */
class ItemModelDisplayTest {
    private static final String ROOT="assets/logos_dimension/";
    private static JsonObject read(String path)throws IOException{
        try(var input=ItemModelDisplayTest.class.getResourceAsStream("/"+ROOT+path)){
            assertNotNull(input,"缺少资源："+path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<String> itemIds()throws Exception{
        var ids=new ArrayList<String>();
        var url=ItemModelDisplayTest.class.getResource("/"+ROOT+"lang/zh_cn.json");
        assertNotNull(url,"测试类路径里没有语言文件");
        if("file".equals(url.getProtocol())){
            var items=Path.of(url.toURI()).getParent().getParent().resolve("items");
            try(var files=Files.list(items)){
                files.filter(p->p.toString().endsWith(".json"))
                     .forEach(p->ids.add(p.getFileName().toString().replaceFirst("\\.json$","")));
            }
        }else{
            var connection=url.openConnection();
            assertTrue(connection instanceof JarURLConnection,"无法识别的资源协议："+url);
            try(var jar=((JarURLConnection)connection).getJarFile()){
                var entries=jar.entries();
                while(entries.hasMoreElements()){
                    String name=entries.nextElement().getName();
                    String prefix=ROOT+"items/";
                    if(name.startsWith(prefix)&&name.endsWith(".json")
                        &&!name.substring(prefix.length()).contains("/"))
                        ids.add(name.substring(prefix.length()).replaceFirst("\\.json$",""));
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    @Test void itemsGetStandardBlockDisplayTransforms()throws Exception{
        var offenders=new ArrayList<String>();
        for(String id:itemIds()){
            var item=read("items/"+id+".json");
            if(!item.has("model"))continue;
            var itemModel=item.getAsJsonObject("model");
            if(!itemModel.has("model"))continue;
            String ref=itemModel.get("model").getAsString();
            if(!ref.startsWith("logos_dimension:block/"))continue;
            String modelPath="models/block/"+ref.substring("logos_dimension:block/".length())+".json";
            var model=read(modelPath);
            if(!model.has("parent")&&!model.has("display")){
                offenders.add(id+" -> "+ref);
            }
        }
        assertTrue(offenders.isEmpty(),
            "这些物品引用的方块模型没有 parent/display，手持和掉落会显示成整块：\n"+String.join("\n",offenders));
    }
}
