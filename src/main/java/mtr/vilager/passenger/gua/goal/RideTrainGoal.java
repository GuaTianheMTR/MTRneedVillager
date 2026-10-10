package mtr.vilager.passenger.gua.goal;

import mtr.vilager.passenger.gua.helper.RidingVillagerManager;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.mtr.mapping.mapper.DirectionHelper;

public class RideTrainGoal extends Goal {

	private static final int SEARCH_RADIUS = 24;
	private static final int COOLDOWN_TICKS = 400;
	private static final int TRIGGER_RANDOM_BOUND = 2400;
	private static final int GIVE_UP_TICKS = 600;
	private static final double SPEED = 0.5;

	private final VillagerEntity villager;
	private BlockPos barrierPos;
	private long lastTryTime;
	private int runningTicks;

	public RideTrainGoal(VillagerEntity villager) {
		this.villager = villager;
	}

	@Override
	public boolean canStart() {
		if (RidingVillagerManager.isRiding(villager.getUuid())) {
			// 在车上（跟着列车走）时不去过闸
			return false;
		}
		final long time = villager.getWorld().getTime();
		if (time - lastTryTime < COOLDOWN_TICKS) {
			return false;
		}
		// “有时”：平均约每 2 分钟随机触发一次
		if (villager.getRandom().nextInt(TRIGGER_RANDOM_BOUND) != 0) {
			return false;
		}
		// 找村民位于其入口侧（zMapped < 0）的最近闸机：
		// 站外的村民找到入口闸机进站，站台内的村民找到出口闸机出站
		final BlockPos nearest = findNearestBarrier();
		if (nearest == null) {
			return false;
		}
		barrierPos = nearest;
		return true;
	}

	@Override
	public void start() {
		runningTicks = 0;
		moveToBarrier();
	}

	@Override
	public boolean shouldContinue() {
		if (++runningTicks > GIVE_UP_TICKS) {
			// 走不到（寻路失败或被堵住）时不让村民永远卡死，超时自动放弃
			return false;
		}
		if (Math.abs(villager.getBlockPos().getY() - barrierPos.getY()) > 3) {
			// 闸机在别的楼层，放弃
			return false;
		}
		return barrierPos != null && isBarrier(barrierPos) && !passedThrough();
	}

	@Override
	public void tick() {
		if (barrierPos != null && isBarrier(barrierPos) && !passedThrough()) {
			moveToBarrier();
		}
	}

	@Override
	public void stop() {
		barrierPos = null;
		lastTryTime = villager.getWorld().getTime();
	}

	private boolean passedThrough() {
		return getZOffset(barrierPos) > 0.5;
	}

	private void moveToBarrier() {
		villager.getNavigation().startMovingTo(barrierPos.getX() + 0.5, barrierPos.getY(), barrierPos.getZ() + 0.5, SPEED);
	}

	private BlockPos findNearestBarrier() {
		final BlockPos center = villager.getBlockPos();
		final BlockPos.Mutable mutable = new BlockPos.Mutable();
		BlockPos nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
			for (int dy = -4; dy <= 4; dy++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					// 只考虑村民位于其入口侧（zMapped < 0）的闸机
					if (isBarrier(mutable) && getZOffset(mutable) < 0) {
						final double distance = villager.squaredDistanceTo(mutable.getX() + 0.5, mutable.getY() + 0.5, mutable.getZ() + 0.5);
						if (distance < nearestDistance) {
							nearestDistance = distance;
							nearest = mutable.toImmutable();
						}
					}
				}
			}
		}
		return nearest;
	}

	private boolean isBarrier(BlockPos pos) {
		// 用类名字符串判断，兼容 MTR 不同版本的包结构
		try {
			return villager.getWorld().getBlockState(pos).getBlock().getClass().getSimpleName().equals("BlockTicketBarrier");
		} catch (Throwable e) {
			return false;
		}
	}

	private double getZOffset(BlockPos pos) {
		final Direction facing = getFacing(pos);
		if (facing == null) {
			return 1;
		}
		// 与闸机碰撞逻辑一致：将相对位置按朝向旋转后取 z 分量，z > 0 表示已越过闸机
		final Vec3d relative = villager.getPos().subtract(pos.getX() + 0.5, 0, pos.getZ() + 0.5);
		return relative.rotateY((float) Math.toRadians(facing.asRotation())).getZ();
	}

	private Direction getFacing(BlockPos pos) {
		try {
			return villager.getWorld().getBlockState(pos).get(DirectionHelper.FACING.data);
		} catch (Throwable e) {
			return null;
		}
	}
}
