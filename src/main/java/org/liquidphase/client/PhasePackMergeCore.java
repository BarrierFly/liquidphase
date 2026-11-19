package org.liquidphase.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/**
 * 资源包兼容合并的**纯 JSON 核心**（0.1.17）：不依赖任何 Minecraft 类，只有 Gson——
 * 合并算法可以在服务端自检（{@code checkPackMergeFragments} 的 fixtures 断言）中直接运行，
 * Minecraft 侧的接线在 {@link PhasePackMerge}。
 *
 * <h2>问题</h2>
 * mod 把相覆盖部件（固相覆盖层/红石外壳/整格相材质）烘焙进
 * {@code assets/minecraft/blockstates/<宿主>.json}。用户资源包优先级高于 mod 资源：
 * 一个**非针对本 mod** 的包替换宿主 blockstate 时整文件生效，相部件全部丢失
 * ——不崩溃、不刷日志，含相状态直接落到包的无条件部件上，相静默隐形。
 *
 * <h2>方案</h2>
 * 在 blockstate 加载点把"获胜定义"与 mod 自带的**片段**
 * （{@code assets/liquidphase/phase_parts/<id>.json}）合并：包只生效于宿主 part，
 * 相部件永远来自 mod：
 * <ol>
 *   <li>获胜定义是 variants 形式 → 先转成等价 multipart（每个变体一条 when 条件，
 *       与 {@code gen_phase_blockstates.py} 的宿主预处理同一套语义）；</li>
 *   <li>按片段的 {@code gates} 给获胜定义的宿主 part 合并 {@code filling} 条件
 *       ——整格/混合宿主的整格 part 必须限制在"宿主可见"的相，否则固相整格覆盖层
 *       藏在宿主模型内部、完全看不见；</li>
 *   <li>追加片段的 {@code parts}（固相覆盖层 + 红石外壳，条件自包含）。</li>
 * </ol>
 *
 * <p>合并语义的版本无关性：≤1.21.1 与 1.21.10+ 的 blockstate 管线都是
 * "按资源栈逐包解析、栈序后者按状态覆盖"，所以只需把**栈顶**（获胜者）的 JSON
 * 原地合并，两条管线都自然生效。
 *
 * <h2>降级约定</h2>
 * 条件合并要求**精确匹配**（键与值串完全相等，含键数）：获胜 part 的 when 与
 * 片段 gate 的 when 不一致（包重排了变体、把多状态并成一条 OR 条件等）时该 gate
 * 不生效——该宿主的固相外观退回旧行为（相被宿主模型挡住），不崩溃、不影响其他相。
 * 红石外壳与内缩覆盖层的部件是自包含条件，不依赖 gate，始终生效。
 */
public final class PhasePackMergeCore {

    private PhasePackMergeCore() {
    }

    // ------------------------------------------------------------------
    // 标记扫描：这份定义是不是已经带相部件（mod 烘焙的 / 针对本 mod 的包）
    // ------------------------------------------------------------------

    /**
     * {@return JSON 里是否出现任何 {@code liquidphase:} 命名空间的字符串引用}
     * <p>
     * mod 烘焙的 blockstate 必然引用 {@code liquidphase:block/redstone_phase_*} 等模型；
     * 针对本 mod 的包同样如此。原版/普通第三方包不会引用这个命名空间。
     * 引用了就说明"获胜定义自带相部件"，无需（也不应）再合并——重复追加部件
     * 会让 multipart 把同一覆盖层画两遍。
     */
    public static boolean referencesLiquidphase(JsonElement element) {
        if (element == null) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            return primitive.isString() && primitive.getAsString().startsWith("liquidphase:");
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (referencesLiquidphase(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (referencesLiquidphase(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 合并入口
    // ------------------------------------------------------------------

    /**
     * {@return 合并后的 multipart 定义；获胜定义两种形式都没有（或无法解析）时返回 null}
     * <p>
     * 输入不被修改；输出是全新对象。
     *
     * @param winner   获胜（栈顶）资源的 blockstate JSON
     * @param fragment 片段 JSON：{@code {"parts": [...], "gates": [{"when": {...}, "with": {...}}]}}
     */
    public static JsonObject merge(JsonObject winner, JsonObject fragment) {
        JsonArray parts;
        if (winner.has("multipart")) {
            parts = winner.getAsJsonArray("multipart").deepCopy();
        } else if (winner.has("variants")) {
            parts = variantParts(winner.getAsJsonObject("variants"));
        } else {
            return null;
        }

        if (fragment.has("gates")) {
            for (JsonElement gateElement : fragment.getAsJsonArray("gates")) {
                JsonObject gate = gateElement.getAsJsonObject();
                JsonObject gateWhen = gate.has("when") ? gate.getAsJsonObject("when") : new JsonObject();
                JsonObject with = gate.getAsJsonObject("with");
                for (int i = 0; i < parts.size(); i++) {
                    JsonObject part = parts.get(i).getAsJsonObject();
                    JsonObject partWhen = part.has("when") ? part.getAsJsonObject("when") : null;
                    if (gateMatches(partWhen, gateWhen)) {
                        part.add("when", andCondition(partWhen, with));
                    }
                }
            }
        }

        if (fragment.has("parts")) {
            for (JsonElement part : fragment.getAsJsonArray("parts")) {
                parts.add(part.deepCopy());
            }
        }

        JsonObject out = new JsonObject();
        out.add("multipart", parts);
        return out;
    }

    // ------------------------------------------------------------------
    // variants → multipart（与 gen_phase_blockstates.py 的宿主预处理同一语义）
    // ------------------------------------------------------------------

    /** 把 variants 对象转成等价的 multipart part 列表（每个变体一条 when 条件）。 */
    static JsonArray variantParts(JsonObject variants) {
        JsonArray parts = new JsonArray();
        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            JsonObject part = new JsonObject();
            JsonObject when = variantKeyToWhen(entry.getKey());
            if (when != null) {
                part.add("when", when);
            }
            part.add("apply", variantEntryToApply(entry.getValue()));
            parts.add(part);
        }
        return parts;
    }

    /** {@return "facing=north,waterlogged=true" 的 when 对象；空键返回 null（无条件）} */
    static JsonObject variantKeyToWhen(String key) {
        key = key.trim();
        if (key.isEmpty()) {
            return null;
        }
        JsonObject when = new JsonObject();
        for (String piece : key.split(",")) {
            piece = piece.trim();
            if (piece.isEmpty()) {
                continue;
            }
            int eq = piece.indexOf('=');
            if (eq <= 0) {
                // 原版 VariantSelector 对畸形键报错；这里同样拒绝（合并失败 → 调用方降级）。
                return null;
            }
            when.addProperty(piece.substring(0, eq).trim(), piece.substring(eq + 1).trim());
        }
        return when.size() == 0 ? null : when;
    }

    /** variants 条目 → multipart 的 apply 值（丢弃 weight，multipart 不支持）。 */
    static JsonElement variantEntryToApply(JsonElement entry) {
        if (entry.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement item : entry.getAsJsonArray()) {
                out.add(variantEntryToApply(item));
            }
            return out;
        }
        JsonObject source = entry.getAsJsonObject();
        JsonObject apply = new JsonObject();
        apply.addProperty("model", source.get("model").getAsString());
        for (String key : new String[]{"x", "y", "z", "uvlock"}) {
            if (source.has(key)) {
                apply.add(key, source.get(key));
            }
        }
        return apply;
    }

    // ------------------------------------------------------------------
    // gate 匹配与条件合并（and_condition 是 gen_phase_blockstates.py 同名函数的移植）
    // ------------------------------------------------------------------

    /**
     * {@return part 的 when 是否被 gate 命中}
     * <p>
     * 两类语义：
     * <ul>
     *   <li><b>gate.when 为空</b>：命中**全部** part——整格宿主专用。整格宿主的每个 part
     *       都在整格集合里，统一门控不可能过度隐藏；原版树叶是无条件单一变体，
     *       第三方包常拆成 per-distance 变体，精确匹配会漏掉它们；</li>
     *   <li><b>gate.when 非空</b>：与 part.when **精确相等**（键集相同、值串相等）——
     *       混合宿主专用。不用"子集匹配"是**有意收紧**：子集匹配会把
     *       {@code "type": "bottom|double"} 这类合并条件整条 gate 住，把不该隐藏宿主的
     *       半砖变体也一起藏掉；字面相等匹配不上时走降级（该 part 不门控，
     *       固相外观退回旧行为，不崩溃）。</li>
     * </ul>
     * 值串含 {@code |}/{@code !} 时按原文比较——原版解析器自己处理这些语法，
     * 这里只做字面相等。
     */
    static boolean gateMatches(JsonObject partWhen, JsonObject gateWhen) {
        int partSize = partWhen == null ? 0 : partWhen.size();
        int gateSize = gateWhen == null ? 0 : gateWhen.size();
        if (gateSize == 0) {
            // 空 gate：只由整格宿主产生（混合宿主的 full 变体必有 when，见生成器）。
            return true;
        }
        if (partSize != gateSize) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : gateWhen.entrySet()) {
            if (!partWhen.has(entry.getKey())) {
                return false;
            }
            JsonElement partValue = partWhen.get(entry.getKey());
            JsonElement gateValue = entry.getValue();
            if (!partValue.isJsonPrimitive() || !gateValue.isJsonPrimitive()
                || !partValue.getAsString().equals(gateValue.getAsString())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCombiner(JsonObject when) {
        return when.size() == 1 && (when.has("AND") || when.has("OR"));
    }

    /**
     * 把 {@code when} 与 {@code extra}（如 {@code {"filling": "none|lava|…"}}）按 AND
     * 合并成**原版合法**的 multipart 条件。原版语法：一个 object 内多个键 = AND；
     * 跨属性组合必须显式 {@code {"AND": [...]}}；AND/OR 键不能与属性键共存。
     */
    static JsonObject andCondition(JsonObject when, JsonObject extra) {
        if (extra == null || extra.size() == 0) {
            return when;
        }
        if (when == null || when.size() == 0) {
            return extra.deepCopy();
        }
        boolean combiner = isCombiner(when);
        if (combiner && when.has("AND")) {
            JsonArray flattened = when.getAsJsonArray("AND").deepCopy();
            flattened.add(extra.deepCopy());
            JsonObject out = new JsonObject();
            out.add("AND", flattened);
            return out;
        }
        boolean conflict = false;
        for (String key : extra.keySet()) {
            if (when.has(key)) {
                conflict = true;
                break;
            }
        }
        if (combiner || conflict) {
            JsonArray entries = new JsonArray();
            entries.add(when.deepCopy());
            entries.add(extra.deepCopy());
            JsonObject out = new JsonObject();
            out.add("AND", entries);
            return out;
        }
        JsonObject merged = when.deepCopy();
        for (Map.Entry<String, JsonElement> entry : extra.entrySet()) {
            merged.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return merged;
    }
}
