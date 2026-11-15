package org.liquidphase.mixin.client;

import net.minecraft.server.packs.resources.ResourceManager;
import org.liquidphase.client.PhasePackMerge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * blockstate 加载入口的 ResourceManager 包装（0.1.17，资源包兼容合并）。
 * <p>
 * 两条管线的 {@code loadBlockStates(ResourceManager, Executor)} 形状一致：
 * <ul>
 *   <li>1.21.10/1.21.11/26.3（{@code BlockStateModelLoader}，公静）：逐包 codec 解析；</li>
 *   <li>≤1.21.1（{@code ModelManager}，私静）：逐包 {@code GsonHelper.parse}。</li>
 * </ul>
 * 两者都只经 {@code listResourceStacks("blockstates", …)} 枚举资源栈，所以在入口
 * 把参数换成 {@link PhasePackMerge#wrap} 的代理即可同时覆盖（栈序"最后者覆盖"，
 * 栈顶定义被合并后两条管线都自然生效）。方法名在各自类里唯一，不需要描述符。
 * <p>
 * ⚠️ 分支写法遵循 AGENTS 约定：**live（未注释）代码必须是 1.21.11 的**——active 版本
 * 直接编译原始源码、{@code //?} 分支只是注释，写反了 active 版本就会挂错目标类
 * （实测：live 分支写成 ModelManager 时 1.21.11 客户端启动即
 * {@code Critical injection failure: could not find any targets matching 'loadBlockStates'}）。
 */
//? if 1.21.10 || 1.21.11 || 26.3 {
@Mixin(net.minecraft.client.resources.model.BlockStateModelLoader.class)
//?} else {
/*@Mixin(net.minecraft.client.resources.model.ModelManager.class)
 *///?}
public abstract class PhaseLoaderMixin {

    @ModifyVariable(method = "loadBlockStates", argsOnly = true, at = @At("HEAD"), ordinal = 0)
    private static ResourceManager liquidphase$wrapResourceManager(ResourceManager manager) {
        return PhasePackMerge.wrap(manager);
    }
}
