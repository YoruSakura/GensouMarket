package net.scarletphantasy.gensouMarket.command;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.PricingGroupStore;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class PricingGroupCommand {
    public static final String PERMISSION = "gensoumarket.admin.group";
    private final GensouMarket plugin;

    public PricingGroupCommand(GensouMarket plugin) { this.plugin = plugin; }

    public void execute(CommandSender sender, String[] args) {
        if (!requirePermission(sender, PERMISSION)) return;
        var config = plugin.getConfigManager();
        if (!config.isPricingGroupsEnabled()) {
            MessageUtil.send(sender, "&c定价分组未启用，请在 config.yml 设置 dynamic-pricing.groups-enabled: true 后重载。");
            return;
        }
        if (args.length < 2) { help(sender); return; }
        var groups = config.getPricingGroups();
        String action = args[1].toLowerCase(Locale.ROOT);
        try {
            if (action.equals("list")) {
                MessageUtil.send(sender, "&6定价分组: &f" + String.join(", ", groups.ids()));
                return;
            }
            if (args.length < 3) { help(sender); return; }
            String id = args[2].toLowerCase(Locale.ROOT);
            if (action.equals("create")) {
                groups.create(id);
                MessageUtil.send(sender, "&a已创建定价分组 &e" + id + "&a，文件: pricing-groups/" + id + ".yml");
                return;
            }
            if (groups.get(id) == null) { MessageUtil.send(sender, "&c未找到该组！"); return; }
            switch (action) {
                case "info" -> {
                    MessageUtil.send(sender, "&6分组: &e" + id);
                    MessageUtil.sendNoPrefix(sender, "&7商店: &f" + String.join(", ", config.getGroupItemIds("shop", id)));
                    MessageUtil.sendNoPrefix(sender, "&7回收: &f" + String.join(", ", config.getGroupItemIds("recycle", id)));
                    for (String key : groups.get(id).getKeys(true)) {
                        if (!groups.get(id).isConfigurationSection(key)) {
                            MessageUtil.sendNoPrefix(sender, "&7" + key + ": &f" + groups.get(id).get(key));
                        }
                    }
                }
                case "delete" -> {
                    if (!config.getGroupItemIds("shop", id).isEmpty() || !config.getGroupItemIds("recycle", id).isEmpty()) {
                        MessageUtil.send(sender, "&c该组仍有物品，请先用 group remove 移出物品再删除组。");
                        return;
                    }
                    groups.delete(id);
                    MessageUtil.send(sender, "&a已删除分组 &e" + id);
                }
                case "set" -> {
                    if (args.length != 6) { help(sender); return; }
                    groups.set(id, module(args[3]), args[4].toLowerCase(Locale.ROOT), args[5]);
                    MessageUtil.send(sender, "&a分组参数已保存并生效；default 表示沿用默认参数。");
                }
                case "add", "remove" -> {
                    if (args.length < 5 || args.length > 6 || (action.equals("remove") && args.length != 5)) {
                        help(sender); return;
                    }
                    String module = module(args[3]);
                    if (!requirePermission(sender, "gensoumarket.admin." + module + ".edit")) return;
                    String itemId = args[4].toLowerCase(Locale.ROOT);
                    if (!PricingGroupStore.validId(itemId)) {
                        throw new IllegalArgumentException("物品 ID 只能使用小写字母、数字、下划线和短横线，最多 64 个字符");
                    }
                    boolean exists = module.equals("shop") ? plugin.getShopManager().getShopItems().containsKey(itemId)
                            : plugin.getRecycleManager().getRecycleItems().containsKey(itemId);
                    if (args.length == 6) {
                        if (exists) throw new IllegalArgumentException("物品 ID 已存在；移入现有物品时不要填写价格，改价请用 setprice");
                        if (!(sender instanceof Player player)) throw new IllegalArgumentException("手持添加只能由玩家执行");
                        ItemStack hand = player.getInventory().getItemInMainHand();
                        if (hand.getType().isAir()) throw new IllegalArgumentException("请手持要添加的物品");
                        double price = positivePrice(args[5]);
                        boolean saved = module.equals("shop")
                                ? plugin.getShopManager().addItem(itemId, hand, price, id)
                                : plugin.getRecycleManager().addItem(itemId, hand, price, id);
                        if (!saved) throw new IllegalArgumentException("添加失败：同款物品或 ID 已注册、价格无效，或配置写入失败；请查看日志");
                        MessageUtil.send(sender, "&a已保存手持物品的完整数据，添加为 &e" + itemId + " &a并归入组 &e" + id);
                    } else {
                        if (!exists) throw new IllegalArgumentException("未找到物品 ID；创建手持物品时请在末尾填写基础价格");
                        if (action.equals("remove") && !config.getGroupItemIds(module, id).contains(itemId)) {
                            throw new IllegalArgumentException("该物品不在此组中");
                        }
                        if (!config.setItemPricingGroup(module, itemId, action.equals("add") ? id : null)) {
                            throw new IllegalArgumentException("保存失败，请查看服务器日志");
                        }
                        MessageUtil.send(sender, action.equals("add") ? "&a物品已归入组 &e" + id
                                : "&a物品已移出该组，商品及其库存保留，沿用单件配置和全局参数。");
                    }
                }
                default -> help(sender);
            }
        } catch (IllegalArgumentException ex) {
            MessageUtil.send(sender, "&c" + ex.getMessage());
        } catch (IOException ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "保存定价分组失败", ex);
            MessageUtil.send(sender, "&c文件保存失败，操作未生效，请查看服务器日志。");
        }
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION) || !plugin.getConfigManager().isPricingGroupsEnabled()) return List.of();
        List<String> values = List.of();
        if (args.length == 2) values = List.of("list", "create", "info", "delete", "set", "add", "remove");
        else if (args.length == 3 && !args[1].equalsIgnoreCase("create")) values = plugin.getConfigManager().getPricingGroups().ids();
        else if (args.length == 4 && List.of("set", "add", "remove").contains(args[1].toLowerCase(Locale.ROOT))) {
            values = List.of("shop", "recycle");
        } else if (args.length == 5 && args[1].equalsIgnoreCase("set")) values = PricingGroupStore.parameters(args[3]);
        else if (args.length == 5 && List.of("add", "remove").contains(args[1].toLowerCase(Locale.ROOT))
                && sender.hasPermission("gensoumarket.admin." + args[3] + ".edit")) {
            if (args[1].equalsIgnoreCase("remove")) values = plugin.getConfigManager().getGroupItemIds(args[3], args[2]);
            else if (args[3].equals("shop")) values = List.copyOf(plugin.getShopManager().getShopItems().keySet());
            else if (args[3].equals("recycle")) values = List.copyOf(plugin.getRecycleManager().getRecycleItems().keySet());
        } else if (args.length == 6 && args[1].equalsIgnoreCase("set")) values = PricingGroupStore.valueSuggestions(args[3], args[4]);
        String input = args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(input)).toList();
    }

    public static double positivePrice(String value) {
        try {
            double price = Double.parseDouble(value);
            if (!Double.isFinite(price) || price <= 0) throw new NumberFormatException();
            return price;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("价格必须为大于 0 的有限数值");
        }
    }

    private static String module(String value) {
        String module = value.toLowerCase(Locale.ROOT);
        if (!module.equals("shop") && !module.equals("recycle")) throw new IllegalArgumentException("物品类型必须为 shop 或 recycle");
        return module;
    }

    private static boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission("gensoumarket.use") && sender.hasPermission(permission)) return true;
        MessageUtil.send(sender, "&c你没有权限执行此命令！");
        return false;
    }

    private static void help(CommandSender sender) {
        MessageUtil.send(sender, "&6/gmarket group list | create <组> | info <组> | delete <组>");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket group set <组> <shop|recycle> <参数> <值|default>");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket group add <组> <shop|recycle> <物品ID> [基础价格]");
        MessageUtil.sendNoPrefix(sender, "&7不填价格：现有物品归组；填写价格：手持物品注册为新 ID，不消耗手持物品。");
        MessageUtil.sendNoPrefix(sender, "&e/gmarket group remove <组> <shop|recycle> <物品ID> &7只移出组，保留商品。");
    }
}
