package example.hello;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public final class HelloWorldPlugin extends JavaPlugin {

    public HelloWorldPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("hello-hytale-plugin setup complete");
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("Hello world from hello-hytale-plugin");
    }

    @Override
    protected void shutdown() {
        getLogger().at(Level.INFO).log("hello-hytale-plugin shutting down");
    }
}
