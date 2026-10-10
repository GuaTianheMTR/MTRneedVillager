package mtr.vilager.passenger.gua.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mtr.vilager.passenger.gua.event.VillagerGoalAdder;
import mtr.vilager.passenger.gua.helper.MTRVillagerHelper;
import mtr.vilager.passenger.gua.helper.RidingVillagerManager;

/** 原生事件：世界 tick（乘车位置跟随）、村民加入/离开世界（注册 AI、清理状态） */
@Mixin(ServerWorld.class)
public class ServerWorldMixin {

	@Inject(method = "tick", at = @At("HEAD"))
	private void mtrvilager$onWorldTick(CallbackInfo ci) {
		try {
			RidingVillagerManager.tick((ServerWorld) (Object) this);
		} catch (Exception e) {
			// 绝不让本模组弄崩服务器
		}
	}

	@Inject(method = "addEntity", at = @At("HEAD"))
	private void mtrvilager$onAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof VillagerEntity villager) {
			try {
				MTRVillagerHelper.initVillagerBalance(villager, (ServerWorld) (Object) this);
				VillagerGoalAdder.registerGoals(villager);
			} catch (Exception e) {
				// 忽略单个村民的初始化失败
			}
		}
	}

	@Inject(method = "removeEntity", at = @At("HEAD"))
	private void mtrvilager$onRemoveEntity(Entity entity, Entity.RemovalReason reason, CallbackInfo ci) {
		if (entity instanceof VillagerEntity villager) {
			RidingVillagerManager.onVillagerUnloaded(villager.getUuid());
		}
	}
}
