package mindustry.content;

import arc.Core;
import arc.graphics.Color;
import mindustry.game.WeatherCycle;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.blocks.environment.OreBlock;
import mindustry.world.blocks.production.Drill;
import mindustry.world.meta.BuildVisibility;
import mindustry.type.Category;

import static mindustry.Vars.*;
import static mindustry.type.ItemStack.with;

/** Content for the opt-in local custom-game cave feature. */
public final class CaveContent{
    public static Item amethyst, adamantite;
    public static Block oreAmethyst, oreAdamantite, amethystDrill, adamantiteDrill;

    private CaveContent(){}

    /** Both switches are required. This content must never be usable in campaign or multiplayer. */
    public static boolean available(){
        return WeatherCycle.localGame() && !state.isCampaign() && state.rules.caves &&
            Core.settings != null && Core.settings.getBool("experimental-caves", false);
    }

    public static void load(){
        amethyst = new Item("amethyst", Color.valueOf("bd80ee")){
            { hardness = 4; cost = 1.2f; }
            @Override public boolean isHidden(){ return !available(); }
        };
        adamantite = new Item("adamantite", Color.valueOf("70d0a5")){
            { hardness = 4; cost = 1.5f; }
            @Override public boolean isHidden(){ return !available(); }
        };
        oreAmethyst = new OreBlock(amethyst);
        oreAdamantite = new OreBlock(adamantite);
        BuildVisibility localCaves = new BuildVisibility(CaveContent::available);
        amethystDrill = new Drill("amethyst-drill"){{
            requirements(Category.production, localCaves, with(Items.copper, 55, Items.silicon, 45, Items.titanium, 30, amethyst, 40));
            size = 3;
            tier = 4;
            drillTime = 190f;
            hasPower = true;
            consumePower(1.8f);
            consumeLiquid(Liquids.water, 0.09f).boost();
        }};
        adamantiteDrill = new Drill("adamantite-drill"){{
            requirements(Category.production, localCaves, with(Items.copper, 90, Items.silicon, 80, Items.titanium, 60, amethyst, 45, adamantite, 60));
            size = 4;
            tier = 5;
            drillTime = 145f;
            drawRim = true;
            hasPower = true;
            itemCapacity = 20;
            consumePower(3.5f);
            consumeLiquid(Liquids.water, 0.1f).boost();
        }};
    }
}
