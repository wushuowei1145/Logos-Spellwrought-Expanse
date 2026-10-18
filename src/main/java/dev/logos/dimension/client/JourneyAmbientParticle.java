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
    private double avoidanceX,avoidanceZ;
    private static final FacingCameraMode GROUND=(rotation,camera,partial)->rotation.rotationX(-(float)Math.PI/2);
    private JourneyAmbientParticle(ClientLevel level,double x,double y,double z,SpriteSet sprites,AmbientMotion.Kind kind,RandomSource random){
        super(level,x,y,z,sprites.first());
        this.kind=kind;this.sprites=sprites;anchorX=x;anchorY=y;anchorZ=z;phase=random.nextDouble()*Math.PI*2;
        lifetime=switch(kind){case FIREFLY->140+random.nextInt(100);case MOTE->100+random.nextInt(80);case GLIMMER->36+random.nextInt(28);case SPORE->100+random.nextInt(60);case SIGIL->90+random.nextInt(30);case RIPPLE->45+random.nextInt(20);case WISP->160+random.nextInt(40);};
        quadSize=switch(kind){case FIREFLY->.15f;case MOTE->.105f;case GLIMMER->.13f;case SPORE->.11f;case SIGIL->.38f;case RIPPLE->.50f;case WISP->.24f;}*(.85f+random.nextFloat()*.3f);
        hasPhysics=false;alpha=0;lastTick=JourneyAtmosphere.frame();
        if(kind==AmbientMotion.Kind.MOTE){
            switch(JourneySeasons.of(level)){
                case 0->setColor(1f,.91f,.67f);
                case 1->setColor(.64f,.95f,.79f);
                case 2->setColor(1f,.78f,.48f);
                default->setColor(.68f,.86f,1f);
            }
        }else if(kind==AmbientMotion.Kind.GLIMMER)setColor(.72f,.94f,1f);
        else if(kind==AmbientMotion.Kind.SPORE)setColor(.65f,.91f,.81f);
        else if(kind==AmbientMotion.Kind.SIGIL){if(JourneySeasons.of(level)==3)setColor(.7f,.89f,1f);else setColor(.94f,.88f,.66f);roll=random.nextFloat()*(float)Math.PI*2;oRoll=roll;}
        else if(kind==AmbientMotion.Kind.RIPPLE)setColor(.72f,.90f,1f);
        else if(kind==AmbientMotion.Kind.WISP)setColor(.67f,.90f,1f);
        var f=AmbientMotion.sample(kind,0,lifetime,phase);setPos(x+f.x(),y+f.y(),z+f.z());xo=this.x;yo=this.y;zo=this.z;
        LIVE.add(this);CREATED[kind.ordinal()]++;
    }
    @SubscribeEvent static void providers(RegisterParticleProvidersEvent event){
        event.registerSpriteSet(DimensionParticles.FIREFLY.get(),sprites->provider(sprites,AmbientMotion.Kind.FIREFLY));
        event.registerSpriteSet(DimensionParticles.MOTE.get(),sprites->provider(sprites,AmbientMotion.Kind.MOTE));
        event.registerSpriteSet(DimensionParticles.GLIMMER.get(),sprites->provider(sprites,AmbientMotion.Kind.GLIMMER));
        event.registerSpriteSet(DimensionParticles.SPORE.get(),sprites->provider(sprites,AmbientMotion.Kind.SPORE));
        event.registerSpriteSet(DimensionParticles.SIGIL.get(),sprites->provider(sprites,AmbientMotion.Kind.SIGIL));
        event.registerSpriteSet(DimensionParticles.RIPPLE.get(),sprites->provider(sprites,AmbientMotion.Kind.RIPPLE));
        event.registerSpriteSet(DimensionParticles.WISP.get(),sprites->provider(sprites,AmbientMotion.Kind.WISP));
    }
    private static ParticleProvider<SimpleParticleType> provider(SpriteSet sprites,AmbientMotion.Kind kind){
        return (options,level,x,y,z,xa,ya,za,random)->{
            var mc=Minecraft.getInstance();int setting=mc.options.particles().get().ordinal();
            int kindCap=AmbientMotion.kindCapacity(kind,setting);
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
    @Override public FacingCameraMode getFacingCameraMode(){return kind==AmbientMotion.Kind.SIGIL||kind==AmbientMotion.Kind.RIPPLE?GROUND:super.getFacingCameraMode();}
    @Override public float getQuadSize(float partial){return quadSize*AmbientMotion.size(kind,age+partial,lifetime);}
    @Override public int getLightCoords(float partialTick){return 0xF000F0;}
    @Override public void tick(){
        lastTick=JourneyAtmosphere.frame();var mc=Minecraft.getInstance();
        if(mc.level!=level||mc.player==null||mc.player.distanceToSqr(x,y,z)>24*24||age++>=lifetime){remove();return;}
        if(kind==AmbientMotion.Kind.FIREFLY&&!AmbientMotion.fireflies(JourneySeasons.of(level),JourneySeasons.ticks(level),true,level.isRaining())){remove();return;}
        xo=x;yo=y;zo=z;oRoll=roll;
        var f=AmbientMotion.sample(kind,age,lifetime,phase);setPos(anchorX+f.x(),anchorY+f.y(),anchorZ+f.z());
        if(kind==AmbientMotion.Kind.WISP){
            double dx=anchorX-mc.player.getX(),dz=anchorZ-mc.player.getZ(),d=Math.hypot(dx,dz);
            double strength=Math.clamp((2-d)/2,0,1)*.7;
            avoidanceX+=(strength*dx/Math.max(.1,d)-avoidanceX)*.06;avoidanceZ+=(strength*dz/Math.max(.1,d)-avoidanceZ)*.06;
            setPos(x+avoidanceX,y,z+avoidanceZ);
        }
        var pos=BlockPos.containing(x,y,z);
        if(!level.hasChunk(pos.getX()>>4,pos.getZ()>>4)||kind!=AmbientMotion.Kind.RIPPLE&&!level.getFluidState(pos).isEmpty()||!level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()){remove();return;}
        if(kind==AmbientMotion.Kind.RIPPLE&&!level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)){remove();return;}
        alpha=f.alpha();
        if(kind==AmbientMotion.Kind.FIREFLY){alpha*=(float)AmbientMotion.night(JourneySeasons.ticks(level));setSprite(sprites.get((age/2)%2,1));}
        if(kind==AmbientMotion.Kind.GLIMMER)roll+=.018f;
        if(kind==AmbientMotion.Kind.SIGIL)roll+=.002f;
    }
}
