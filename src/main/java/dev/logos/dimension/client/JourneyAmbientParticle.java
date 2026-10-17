package dev.logos.dimension.client;

import dev.logos.dimension.DimensionParticles;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.JourneySeasons;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

@EventBusSubscriber(modid=LogosDimension.ID,value=Dist.CLIENT)
public final class JourneyAmbientParticle extends SingleQuadParticle {
    private static final Set<JourneyAmbientParticle> LIVE=new LinkedHashSet<>();
    private static final long[] CREATED=new long[AmbientMotion.Kind.values().length];
    private final AmbientMotion.Kind kind;
    private final SpriteSet sprites;
    private final double anchorX,anchorY,anchorZ,phase;
    private long lastTick;
    private JourneyAmbientParticle(ClientLevel level,double x,double y,double z,SpriteSet sprites,AmbientMotion.Kind kind,RandomSource random){
        super(level,x,y,z,sprites.first());
        this.kind=kind;this.sprites=sprites;anchorX=x;anchorY=y;anchorZ=z;phase=random.nextDouble()*Math.PI*2;
        lifetime=switch(kind){case FIREFLY->140+random.nextInt(100);case MOTE->100+random.nextInt(80);case GLIMMER->36+random.nextInt(28);};
        quadSize=switch(kind){case FIREFLY->.15f;case MOTE->.105f;case GLIMMER->.13f;}*(.85f+random.nextFloat()*.3f);
        hasPhysics=false;alpha=0;lastTick=JourneyAtmosphere.frame();
        if(kind==AmbientMotion.Kind.MOTE){
            switch(JourneySeasons.of(level)){
                case 0->setColor(1f,.91f,.67f);
                case 1->setColor(.64f,.95f,.79f);
                case 2->setColor(1f,.78f,.48f);
                default->setColor(.68f,.86f,1f);
            }
        }else if(kind==AmbientMotion.Kind.GLIMMER)setColor(.72f,.94f,1f);
        var f=AmbientMotion.sample(kind,0,lifetime,phase);setPos(x+f.x(),y+f.y(),z+f.z());xo=this.x;yo=this.y;zo=this.z;
        LIVE.add(this);CREATED[kind.ordinal()]++;
    }
    @SubscribeEvent static void providers(RegisterParticleProvidersEvent event){
        event.registerSpriteSet(DimensionParticles.FIREFLY.get(),sprites->provider(sprites,AmbientMotion.Kind.FIREFLY));
        event.registerSpriteSet(DimensionParticles.MOTE.get(),sprites->provider(sprites,AmbientMotion.Kind.MOTE));
        event.registerSpriteSet(DimensionParticles.GLIMMER.get(),sprites->provider(sprites,AmbientMotion.Kind.GLIMMER));
    }
    private static ParticleProvider<SimpleParticleType> provider(SpriteSet sprites,AmbientMotion.Kind kind){
        return (options,level,x,y,z,xa,ya,za,random)->{
            var mc=Minecraft.getInstance();int setting=mc.options.particles().get().ordinal();
            int kindCap=setting==1?(kind==AmbientMotion.Kind.FIREFLY?14:kind==AmbientMotion.Kind.MOTE?12:4):(kind==AmbientMotion.Kind.FIREFLY?40:kind==AmbientMotion.Kind.MOTE?32:8);
            if(!level.dimension().equals(LogosDimension.CLOUDSEA)||LIVE.size()>=AmbientMotion.capacity(setting)||active(kind)>=kindCap)return null;
            return new JourneyAmbientParticle(level,x,y,z,sprites,kind,random);
        };
    }
    public static int active(){return LIVE.size();}
    public static int active(AmbientMotion.Kind kind){int n=0;for(var p:LIVE)if(p.kind==kind)n++;return n;}
    public static long created(AmbientMotion.Kind kind){return CREATED[kind.ordinal()];}
    public static long created(){long total=0;for(long n:CREATED)total+=n;return total;}
    static void clear(){for(var p:List.copyOf(LIVE))p.remove();}
    static void trim(int capacity){
        int kept=0;
        for(var p:List.copyOf(LIVE)){
            // Native resource/particle clearing can discard particles without calling remove().
            if(!p.isAlive()||JourneyAtmosphere.frame()-p.lastTick>3||kept>=capacity)p.remove();else kept++;
        }
    }
    @Override public void remove(){super.remove();LIVE.remove(this);}
    @Override public Layer getLayer(){return Layer.TRANSLUCENT;}
    @Override public int getLightCoords(float partialTick){return 0xF000F0;}
    @Override public void tick(){
        lastTick=JourneyAtmosphere.frame();var mc=Minecraft.getInstance();
        if(mc.level!=level||mc.player==null||mc.player.distanceToSqr(x,y,z)>24*24||age++>=lifetime){remove();return;}
        if(kind==AmbientMotion.Kind.FIREFLY&&!AmbientMotion.fireflies(JourneySeasons.of(level),JourneySeasons.ticks(level),true,level.isRaining())){remove();return;}
        xo=x;yo=y;zo=z;oRoll=roll;
        var f=AmbientMotion.sample(kind,age,lifetime,phase);setPos(anchorX+f.x(),anchorY+f.y(),anchorZ+f.z());
        var pos=BlockPos.containing(x,y,z);
        if(!level.hasChunk(pos.getX()>>4,pos.getZ()>>4)||!level.getFluidState(pos).isEmpty()||!level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()){remove();return;}
        alpha=f.alpha();
        if(kind==AmbientMotion.Kind.FIREFLY){alpha*=(float)AmbientMotion.night(JourneySeasons.ticks(level));setSprite(sprites.get((age/2)%2,1));}
        if(kind==AmbientMotion.Kind.GLIMMER)roll+=.018f;
    }
}
