package dev.logos.dimension.client;

import java.util.List;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.base.GeoRenderState;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.npc.MysticalCat;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

/**
 * 灵猫：同一套模型，按毛色换贴图（{@code textures/entity/mystical_cat/<毛色>.png}，UV 完全相同）。
 * 网格的透明判断读的是默认贴图（{@code getTextureResource(null)}），各毛色的透明像素一样，所以共用一份网格。
 */
public class MysticalCatRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<MysticalCat, R> {
    private static final DataTicket<Integer> COAT = DataTicket.create("logos_dimension_mystical_cat_coat", Integer.class);
    private static final List<Identifier> TEXTURES = MysticalCat.COATS.stream()
            .map(c -> LogosDimension.id("textures/entity/mystical_cat/" + c + ".png")).toList();

    public MysticalCatRenderer(EntityRendererProvider.Context context) {
        super(context, new CoatModel());
    }

    @Override
    public void addRenderData(MysticalCat cat, Void relatedObject, R state, float partialTick) {
        super.addRenderData(cat, relatedObject, state, partialTick);
        state.addGeckolibData(COAT, cat.coat());
    }

    private static final class CoatModel extends DefaultedEntityGeoModel<MysticalCat> {
        CoatModel() {
            super(LogosDimension.id("mystical_cat"));
        }

        @Override
        public Identifier getTextureResource(GeoRenderState state) {
            if (state == null) return super.getTextureResource(null);
            int coat = state.getOrDefaultGeckolibData(COAT, -1);
            return coat >= 0 && coat < TEXTURES.size() ? TEXTURES.get(coat) : super.getTextureResource(state);
        }
    }
}
