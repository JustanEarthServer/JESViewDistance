package dev.ciel.jESViewDistance;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class JESViewDistance extends JavaPlugin implements Listener {

    private static final int MIN = 2;
    private static final int MAX = 32;

    private record Rank(String group, int view, int simulation) {}

    private List<Rank> ranks = List.of();
    private LuckPerms luckPerms;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRanks();

        luckPerms = LuckPermsProvider.get();
        // Fires when a player's groups change, so promotions apply instantly.
        luckPerms.getEventBus().subscribe(this, UserDataRecalculateEvent.class,
                event -> refreshLater(event.getUser().getUniqueId()));

        getServer().getPluginManager().registerEvents(this, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command(), "Per-group view distance", List.of("vd")));

        getServer().getOnlinePlayers().forEach(this::apply);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    private void refreshLater(UUID uuid) {
        getServer().getScheduler().runTask(this, () -> {
            Player player = getServer().getPlayer(uuid);
            if (player != null) {
                apply(player);
            }
        });
    }

    private void loadRanks() {
        reloadConfig();
        List<Rank> loaded = new ArrayList<>();
        ConfigurationSection section = getConfig().getConfigurationSection("groups");
        if (section != null) {
            for (String group : section.getKeys(false)) {
                int view = clamp(group, "view-distance",
                        section.getInt(group + ".view-distance", getServer().getViewDistance()));
                int simulation = clamp(group, "simulation-distance",
                        section.getInt(group + ".simulation-distance", getServer().getSimulationDistance()));
                if (simulation > view) {
                    getLogger().warning(group + " has a higher simulation-distance than view-distance, chunks past "
                            + view + " won't load so they can't tick either");
                }
                loaded.add(new Rank(group.toLowerCase(), view, simulation));
            }
        }
        ranks = List.copyOf(loaded);
    }

    private int clamp(String group, String key, int value) {
        int clamped = Math.clamp(value, MIN, MAX);
        if (clamped != value) {
            getLogger().warning(group + "." + key + " is " + value + ", using " + clamped + " (must be " + MIN + "-" + MAX + ")");
        }
        return clamped;
    }

    private Rank rankOf(Player player) {
        User user = luckPerms.getPlayerAdapter(Player.class).getUser(player);
        Set<String> groups = user.getInheritedGroups(user.getQueryOptions()).stream()
                .map(Group::getName)
                .collect(Collectors.toSet());

        for (Rank rank : ranks) {
            if (groups.contains(rank.group())) {
                return rank;
            }
        }
        return new Rank("server default", getServer().getViewDistance(), getServer().getSimulationDistance());
    }

    private void apply(Player player) {
        Rank rank = rankOf(player);
        set(player, rank.view(), rank.simulation());
    }

    private static void set(Player player, int view, int simulation) {
        if (player.getViewDistance() != view) {
            player.setViewDistance(view);
        }
        if (player.getSimulationDistance() != simulation) {
            player.setSimulationDistance(simulation);
        }
    }

    private LiteralCommandNode<CommandSourceStack> command() {
        return Commands.literal("viewdistance")
                .requires(source -> source.getSender().hasPermission("jesviewdistance.admin"))
                .then(Commands.literal("reload").executes(ctx -> {
                    loadRanks();
                    getServer().getOnlinePlayers().forEach(this::apply);
                    reply(ctx, "<green>Config reloaded and applied to everyone online.");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("check")
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(ctx -> {
                            Player player = player(ctx);
                            Rank rank = rankOf(player);
                            reply(ctx, "<gray>" + player.getName() + ": view <white>" + player.getViewDistance()
                                    + "</white>, simulation <white>" + player.getSimulationDistance()
                                    + "</white>, client render distance <white>" + player.getClientViewDistance()
                                    + "</white>\n<gray>Group <white>" + rank.group() + "</white> gives view <white>"
                                    + rank.view() + "</white>, simulation <white>" + rank.simulation());
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .then(Commands.argument("view", IntegerArgumentType.integer(MIN, MAX))
                                        .then(Commands.argument("simulation", IntegerArgumentType.integer(MIN, MAX)).executes(ctx -> {
                                            Player player = player(ctx);
                                            int view = IntegerArgumentType.getInteger(ctx, "view");
                                            int simulation = IntegerArgumentType.getInteger(ctx, "simulation");
                                            set(player, view, simulation);
                                            reply(ctx, "<green>" + player.getName() + " now has view " + view + ", simulation "
                                                    + simulation + ".\n<gray>This lasts until they rejoin, their groups change or you run reset.");
                                            return Command.SINGLE_SUCCESS;
                                        })))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(ctx -> {
                            Player player = player(ctx);
                            apply(player);
                            reply(ctx, "<green>" + player.getName() + " is back to their group's distances.");
                            return Command.SINGLE_SUCCESS;
                        })))
                .build();
    }

    private static Player player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().getSender().sendRichMessage(message);
    }
}
