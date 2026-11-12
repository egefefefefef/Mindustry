package mindustry.ui.dialogs;

import arc.Core;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.layout.Table;
import arc.util.Strings;
import mindustry.content.UnitTypes;
import mindustry.core.DevTestTools;
import mindustry.gen.Icon;
import mindustry.type.UnitType;
import mindustry.ui.Styles;

import static mindustry.Vars.*;

/** Single-player tools for repeatable load testing. */
public class DeveloperPanel extends BaseDialog{
    private final DevTestTools tools = new DevTestTools();
    private UnitType type = UnitTypes.dagger;
    private String count = "50";
    private boolean enemy;

    public DeveloperPanel(){
        super("@devpanel.title");
        addCloseButton();
        shown(this::rebuild);
    }

    private void rebuild(){
        cont.clear();
        cont.pane(t -> {
            t.defaults().pad(5f);
            t.add("@devpanel.warning").wrap().width(480f).row();
            t.button(Core.bundle.format("devpanel.unit", type.localizedName), Icon.pencil, Styles.flatt, this::chooseUnit).width(440f).height(54f).row();
            t.button(Core.bundle.get(enemy ? "devpanel.enemy" : "devpanel.ally"), Styles.flatt, () -> {
                enemy = !enemy;
                rebuild();
            }).width(440f).height(50f).row();
            t.table(c -> {
                c.add("@devpanel.count").padRight(10f);
                c.field(count, text -> count = text).width(150f).height(50f);
            }).row();
            t.button("@devpanel.spawn", Icon.add, () -> {
                if(!tools.available() || player == null || player.dead()){
                    ui.showInfo("@devpanel.unavailable");
                    return;
                }
                int amount = Strings.parseInt(count, -1);
                var result = tools.spawn(type, enemy ? state.rules.waveTeam : player.team(), player.x, player.y, amount);
                showResult(result);
            }).width(440f).height(55f).row();
            t.button("@devpanel.clear", Icon.trash, () -> {
                if(!tools.available()) { ui.showInfo("@devpanel.unavailable"); return; }
                ui.showConfirm("@devpanel.clearconfirm", () -> showResult(tools.clear()));
            }).width(440f).height(55f).row();
            t.label(() -> Core.bundle.format("devpanel.tracked", tools.trackedCount())).row();
            t.button("@devpanel.wave", Icon.right, () -> {
                if(!tools.available()) { ui.showInfo("@devpanel.unavailable"); return; }
                ui.showConfirm("@devpanel.waveconfirm", () -> showResult(tools.nextWave()));
            }).width(440f).height(55f).row();
            t.button("@devpanel.metrics", Icon.settings, () -> Core.settings.put("showperformance", true)).width(440f).height(55f).row();
        }).grow().maxWidth(540f);
    }

    private void chooseUnit(){
        BaseDialog select = new BaseDialog("@devpanel.choose");
        select.addCloseButton();
        select.cont.pane(list -> {
            for(UnitType unit : content.units()){
                if(unit.hidden || unit.internal || unit.constructor == null) continue;
                list.button(unit.localizedName, new TextureRegionDrawable(unit.uiIcon), Styles.grayt, 32f, () -> {
                    type = unit;
                    select.hide();
                    rebuild();
                }).width(400f).height(50f).row();
            }
        }).grow().maxWidth(450f);
        select.show();
    }

    private void showResult(DevTestTools.Result result){
        if(result != DevTestTools.Result.ok) ui.showInfo("@devpanel." + result.name());
    }
}
