package org.liquidphase.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import org.liquidphase.LiquidPhase;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.sync.PhaseIds;

/**
 * 资源包兼容合并的 Minecraft 接线（0.1.17，客户端）。
 *
 * <h2>注入点</h2>
 * 两条 blockstate 管线的入口都收 {@link ResourceManager} 且都只通过
 * {@code listResourceStacks("blockstates", …)} 枚举资源栈（栈序低→高优先级，
 * 解析后按状态"栈序后者覆盖"）：
 * <ul>
 *   <li>≤1.21.1：{@code ModelManager.loadBlockStates}（私静，逐包 {@code GsonHelper.parse}）；</li>
 *   <li>1.21.10/1.21.11/26.3：{@code BlockStateModelLoader.loadBlockStates}
 *       （公静，逐包 {@code BlockModelDefinition.CODEC} 解析）。</li>
 * </ul>
 * 所以只需在入口把 {@code resourceManager} 参数换成一个**代理**：其他方法全部透传，
 * 唯独 {@code listResourceStacks("blockstates")} 的结果做后处理——对每个相容宿主，
 * 若栈顶（获胜者）定义不是 mod 烘焙的（不含 {@code liquidphase:} 引用），就用
 * {@link PhasePackMergeCore} 把片段合并进栈顶 JSON，包只生效于宿主 part。
 * 合并算法见 {@link PhasePackMergeCore}；注入 mixin 见 {@code PhaseLoaderMixin}。
 *
 * <h2>为什么用动态代理</h2>
 * {@code ResourceManager} 是接口，但方法签名在 26.3 有变
 * （{@code listResourceStacks} 的第二参从 {@code Predicate} 换成 {@code Selector}）。
 * 代理按方法名分派、其余透传，一处实现覆盖全部版本，不给 stonecutter 添分支；
 * {@code getResource} 的 {@code Optional<Resource>}、26.3 的 {@code Selector}
 * 都不用在 mod 源码里点名。
 *
 * <h2>副作用与性能</h2>
 * 仅在资源重载（进入世界 / F3+T / 切包）时执行；只对相容宿主且栈顶无
 * {@code liquidphase:} 引用的栈读一次栈顶 JSON（约几十毫秒量级），正常路径
 * （无第三方包覆盖宿主 blockstate）零写入、只多一次字符串扫描。
 * 片段缺失（第三方 mod 的相容宿主）静默跳过。
 */
public final class PhasePackMerge {

    private PhasePackMerge() {
    }

    /** {@return 包了合并逻辑的 ResourceManager；功能关闭或已是代理时原样返回} */
    public static ResourceManager wrap(ResourceManager inner) {
        if (!LiquidPhaseConfig.get().packMerge) {
            // 配置关掉时**必须留痕**：否则表现为"资源包下相部件整体消失，却没有任何日志"，
            // 与"包没有被识别成覆盖宿主"完全无法区分（排查时踩过）。
            LiquidPhase.LOGGER.info("[Liquid Phase] pack merge is disabled by config (packMerge=false); "
                + "phase parts will be lost for any host blockstate a resource pack overrides");
            return inner;
        }
        if (Proxy.isProxyClass(inner.getClass())) {
            return inner;
        }
        LiquidPhase.LOGGER.info("[Liquid Phase] pack merge active: wrapping resource manager for blockstate loading");
        return (ResourceManager) Proxy.newProxyInstance(
            PhasePackMerge.class.getClassLoader(),
            new Class<?>[]{ResourceManager.class},
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(inner, args);
                }
                if (isBlockstateStacksCall(args)) {
                    Object result = method.invoke(inner, args);
                    if (isStackMap(result)) {
                        return mergeStacks(inner, (Map<?, ?>) result);
                    }
                    return result;
                }
                return method.invoke(inner, args);
            });
    }

    // ------------------------------------------------------------------
    // 调用识别（0.1.20 修：按实参识别，不按方法名）
    // ------------------------------------------------------------------

    /**
     * {@return 这次调用是否为"枚举 blockstates 资源栈"}
     * <p>
     * ⚠️ **绝不能按 {@code method.getName()} 分派**：方法名是开发环境的 Yarn 名
     * （{@code listResourceStacks}），生产环境是 intermediary 名（1.21.11 实测为
     * {@code method_41265}），loom 只重映射 mixin 注解里的目标、不会重映射普通
     * Java 字符串。0.1.19 及以前按名比较，生产（launcher）环境必然失配——表现为
     * 日志有 "pack merge active" 却永远没有 "blockstate merge scan"，第三方包覆盖
     * 宿主 blockstate 时相部件整体丢失（熔岩相走原版流体渲染不受影响，实机症状）。
     * <p>
     * 这里只看**运行时实参**：{@code listResourceStacks} 是 ResourceManager 上唯一
     * 两参、首参为目录名字符串的方法，而 {@code "blockstates"} 是与映射无关的运行时值。
     * 若未来某版本出现同形状方法（首参为字符串的枚举类方法），再补返回类型/过滤器类型判断。
     */
    public static boolean isBlockstateStacksCall(Object[] args) {
        return args != null && args.length == 2
            && args[0] instanceof String dir && "blockstates".equals(dir);
    }

    /**
     * {@return 结果是否为"栈表"（值是 {@code List<Resource>} 的 Map）}
     * <p>
     * 用于把 {@code listResourceStacks}（值是 List）与 {@code listResources}
     * （值是单个 Resource）区分开：两者的方法签名同为 {@code (String, 过滤器)}，
     * 只有结果形状不同。空表（{@code blockstates} 目录不存在，如服务端数据包资源）
     * 返回 false 直接透传，避免打出无意义的 scan 行。
     */
    public static boolean isStackMap(Object result) {
        if (!(result instanceof Map<?, ?> map)) {
            return false;
        }
        for (Object value : map.values()) {
            return value instanceof List;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 栈后处理
    // ------------------------------------------------------------------

    /**
     * 对 blockstates 资源栈逐栈检查：栈顶定义不含 {@code liquidphase:} 引用
     * （= 第三方包整体覆盖了宿主 blockstate）时，把 mod 片段合并进栈顶资源。
     * 返回结构与入参一致（{@code id → List<Resource>}），仅替换受影响的栈顶元素。
     */
    private static Map<?, ?> mergeStacks(ResourceManager inner, Map<?, ?> stacks) throws Exception {
        Map<Object, Object> out = new LinkedHashMap<>();
        List<String> mergedIds = new ArrayList<>();
        List<String> lostIds = new ArrayList<>();
        int hosts = 0;
        int badJson = 0;
        int selfBaked = 0;
        int noFragment = 0;
        for (Map.Entry<?, ?> entry : stacks.entrySet()) {
            Object id = entry.getKey();
            List<?> stack = (List<?>) entry.getValue();
            // 栈 key 是**文件 id**（如 minecraft:blockstates/oak_stairs.json），
            // 先剥掉 blockstates/ 前缀与 .json 后缀得到方块 id，再去注册表查宿主身份。
            String blockName = blockNameOf(String.valueOf(id));
            if (blockName == null || stack.isEmpty()) {
                out.put(id, stack);
                continue;
            }
            hosts++;
            Resource top = (Resource) stack.get(stack.size() - 1);
            JsonObject winner;
            try (BufferedReader reader = top.openAsReader()) {
                winner = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception e) {
                // 让原版管线自己报这份坏 JSON 的错，这里不拦截。
                badJson++;
                out.put(id, stack);
                continue;
            }
            if (PhasePackMergeCore.referencesLiquidphase(winner)) {
                // 栈顶已经是 mod 烘焙的定义（或针对本 mod 的包），相部件自带，不再合并。
                selfBaked++;
                out.put(id, stack);
                continue;
            }
            JsonObject fragment = readFragment(inner, blockName);
            if (fragment == null) {
                // 第三方 mod 的相容宿主没有片段（生成器只为原版宿主产出），静默跳过。
                noFragment++;
                out.put(id, stack);
                continue;
            }
            JsonObject merged = PhasePackMergeCore.merge(winner, fragment);
            if (merged == null) {
                lostIds.add(blockName + " (" + top.sourcePackId() + ")");
                out.put(id, stack);
                continue;
            }
            byte[] payload = merged.toString().getBytes(StandardCharsets.UTF_8);
            List<Object> newStack = new ArrayList<>(stack);
            newStack.set(newStack.size() - 1,
                new Resource(top.source(), () -> new ByteArrayInputStream(payload)));
            out.put(id, newStack);
            mergedIds.add(blockName);
        }
        // 每次资源重载都打一行汇总：这是"相部件为什么没进 blockstate"的唯一可靠判据，
        // 只打成功/失败两组列表时，"全部被静默跳过"看起来和"根本没进合并"一模一样。
        LiquidPhase.LOGGER.info("[Liquid Phase] blockstate merge scan: {} stacks, {} phase hosts "
                + "({} merged, {} untouched-because-already-baked, {} no-fragment, {} bad-json, {} unmergeable)",
            stacks.size(), hosts, mergedIds.size(), selfBaked, noFragment, badJson, lostIds.size());
        if (!lostIds.isEmpty()) {
            LiquidPhase.LOGGER.warn(
                "[Liquid Phase] phase overlay could not be merged into {} foreign blockstate(s); "
                    + "phase visuals fall back to vanilla behaviour for these hosts: {}",
                lostIds.size(), truncated(lostIds));
        }
        if (!mergedIds.isEmpty()) {
            LiquidPhase.LOGGER.info(
                "[Liquid Phase] merged phase overlays into {} foreign blockstate(s) "
                    + "(packs now only affect the host part): {}",
                mergedIds.size(), truncated(mergedIds));
        }
        return out;
    }

    /** {@return 是否为相容宿主方块} */
    private static boolean isPhaseHost(String namespace, String blockName) {
        Block block = blockFor(namespace, blockName);
        return block instanceof PhaseCapable
            && PhaseHelper.hasPhaseProperty(block.defaultBlockState());
    }

    private static JsonObject readFragment(ResourceManager inner, String blockName) throws Exception {
        var resource = inner.getResource(PhaseIds.id("phase_parts/" + blockName + ".json"));
        if (resource.isEmpty()) {
            return null;
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /**
     * {@return 文件 id（如 {@code minecraft:blockstates/oak_stairs.json}）剥出方块路径
     * {@code oak_stairs}；非 blockstates 目录下的 JSON 返回 null}
     */
    private static String blockNameOf(String fileId) {
        int slash = fileId.indexOf(':');
        if (slash < 0 || !fileId.endsWith(".json")) {
            return null;
        }
        String namespace = fileId.substring(0, slash);
        String path = fileId.substring(slash + 1);
        if (!path.startsWith("blockstates/")) {
            return null;
        }
        String blockName = path.substring("blockstates/".length(), path.length() - ".json".length());
        if (blockName.isEmpty()) {
            return null;
        }
        return isPhaseHost(namespace, blockName) ? blockName : null;
    }

    /** 摘要日志最多列 10 个 id，避免大包刷屏。 */
    private static List<String> truncated(List<String> ids) {
        return ids.size() <= 10 ? ids : ids.subList(0, 10);
    }

    // ------------------------------------------------------------------
    // id 类型适配（ResourceLocation ≤1.21.10 / Identifier 1.21.11+；stonecutter 分支）
    // ------------------------------------------------------------------

    // 扁平分支（不嵌套）：ResourceLocation(String,String) 构造器只有 1.19.4/1.20.1 是公开的，
    // 1.21.1 起必须走 fromNamespaceAndPath；1.21.10 起 Registry.get 改名 getValue；
    // 1.21.11 起 ResourceLocation 改名 Identifier。
    //? if 1.21.11 || 26.3 {
    private static Block blockFor(String namespace, String blockName) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(namespace, blockName));
    }
    //?} elif 1.21.10 {
    /*private static Block blockFor(String namespace, String blockName) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(namespace, blockName));
    }
     *///?} elif 1.21.1 {
    /*private static Block blockFor(String namespace, String blockName) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(namespace, blockName));
    }
     *///?} else {
    /*private static Block blockFor(String namespace, String blockName) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            new net.minecraft.resources.ResourceLocation(namespace, blockName));
    }
     *///?}
}
