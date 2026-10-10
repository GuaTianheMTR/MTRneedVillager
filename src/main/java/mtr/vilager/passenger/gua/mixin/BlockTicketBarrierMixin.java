package mtr.vilager.passenger.gua.mixin;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Entity;
import org.mtr.mapping.holder.Property;
import org.mtr.mapping.holder.ShapeContext;
import org.mtr.mapping.holder.VoxelShape;
import org.mtr.mapping.holder.VoxelShapes;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mod.block.BlockTicketBarrier;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.data.TicketSystem.EnumTicketBarrierOpen;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockTicketBarrier.class)
public class BlockTicketBarrierMixin {

	@Inject(method = "onEntityCollision2", at = @At("HEAD"), cancellable = true, remap = false)
	private void mtrvilager$allowVillagerThrough(BlockState state, World world, BlockPos pos, Entity entity, CallbackInfo ci) {
		if (world.isClient() || !(entity.data instanceof VillagerEntity)) {
			return;
		}

		final Direction facing = IBlock.getStatePropertySafe(state, DirectionHelper.FACING);
		final double zMapped = entity.getPos()
				.subtract(pos.getX() + 0.5, 0, pos.getZ() + 0.5)
				.rotateY((float) Math.toRadians(facing.asRotation()))
				.getZMapped();

		final Property<EnumTicketBarrierOpen> openProperty = new Property<>(BlockTicketBarrier.OPEN.data);
		final EnumTicketBarrierOpen openState = state.get(openProperty);

		if ((openState == EnumTicketBarrierOpen.OPEN || openState == EnumTicketBarrierOpen.OPEN_CONCESSIONARY) && zMapped > 0.3) {
			// 村民已越过闸机，关闭闸门（进站或出站完成，与原版玩家逻辑一致）
			ci.cancel();
			world.setBlockState(pos, state.with(openProperty, EnumTicketBarrierOpen.CLOSED));
		} else if (openState == EnumTicketBarrierOpen.CLOSED && zMapped < 0) {
			// 村民从闸机入口侧（zMapped < 0）触碰关闭的闸机：开门并安排 40 tick（2 秒）后自动关闭
			ci.cancel();
			world.setBlockState(pos, state.with(openProperty, EnumTicketBarrierOpen.OPEN));
			if (world.data instanceof ServerWorld serverWorld) {
				serverWorld.scheduleBlockTick(pos.data, state.data.getBlock(), 40);
			}
		}
	}

	@Inject(method = "getCollisionShape2", at = @At("RETURN"), cancellable = true, remap = false)
	private void mtrvilager$villagerPassesThrough(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx, CallbackInfoReturnable<VoxelShape> cir) {
		// 对村民而言闸机没有碰撞体：村民才能走进闸机方块、触发 onEntityCollision2 开门，
		// 否则寻路会把闸机当障碍，村民永远停在门口
		final net.minecraft.entity.Entity entity = ctx.data instanceof net.minecraft.block.EntityShapeContext entityContext
				? entityContext.getEntity() : null;
		if (entity instanceof VillagerEntity) {
			cir.setReturnValue(VoxelShapes.empty());
		}
	}
}
