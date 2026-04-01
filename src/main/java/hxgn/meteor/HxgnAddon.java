package hxgn.meteor;

import hxgn.meteor.modules.AutoElytraReplace;
import hxgn.meteor.modules.AutoMend;
import hxgn.meteor.modules.AutoToggle;
import hxgn.meteor.modules.FutureTotem;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class HxgnAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("hxgn");

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        Modules.get().add(new AutoToggle());
        Modules.get().add(new AutoMend());
        Modules.get().add(new AutoElytraReplace());
        Modules.get().add(new FutureTotem());
    }

    @Override
    public String getPackage() {
        return "hxgn.meteor";
    }
}
