package mtr.vilager.passenger.gua.goal;

import mtr.vilager.passenger.gua.helper.RidingVillagerManager;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.BlockPos;

public class TicketMachineGoal extends Goal {

	private static final int SEARCH_RADIUS = 24;
	private static final int COOLDOWN_TICKS = 400;
	private static final int TRIGGER_RANDOM_BOUND = 2400;
	private static final int GIVE_UP_TICKS = 600;
	private static final int STAND_TICKS_MIN = 60;
	private static final int STAND_TICKS_MAX = 120;
	private static final double ARRIVE_DISTANCE = 2.2;
	private static final double SPEED = 0.5;

	private final VillagerEntity villager;
	private BlockPos machinePos;
	private long lastTryTime;
	private int runningTicks;
	private int standingTicks;
	private int requiredStandTicks;

	public TicketMachineGoal(VillagerEntity villager) {
		this.villager = villager;
	}

	@Override
	public boolean canStart() {
		if (RidingVillagerManager.isRiding(villager.getUuid())) {
			return false;
		}
		final long time = villager.getWorld().getTime();
		if (time - lastTryTime < COOLDOWN_TICKS) {
			return false;
		}
		// “偶尔”：平均约每 2 分钟随机触发一次
		if (villager.getRandom().nextInt(TRIGGER_RANDOM_BOUND) != 0) {
			return false;
		}
		final BlockPos nearest = findNearestTicketMachine();
		if (nearest == null) {
			return false;
		}
		machinePos = nearest;
		return true;
	}

	@Override
	public void start() {
		runningTicks = 0;
		standingTicks = 0;
		requiredStandTicks = STAND_TICKS_MIN + villager.getRandom().nextInt(STAND_TICKS_MAX - STAND_TICKS_MIN + 1);
	}

	@Override
	public boolean shouldContinue() {
		if (++runningTicks > GIVE_UP_TICKS) {
			return false;
		}
		return machinePos != null && isTicketMachine(machinePos);
	}

	@Override
	public void tick() {
		if (machinePos == null || !isTicketMachine(machinePos)) {
			return;
		}
		final double dx = villager.getX() - (machinePos.getX() + 0.5);
		final double dz = villager.getZ() - (machinePos.getZ() + 0.5);
		if (dx * dx + dz * dz < ARRIVE_DISTANCE * ARRIVE_DISTANCE) {
			// 到了售票机前：站几秒钟（模拟买票），站够了就走
			villager.getNavigation().stop();
			if (++standingTicks >= requiredStandTicks) {
				runningTicks = GIVE_UP_TICKS + 1;
			}
			return;
		}
		villager.getNavigation().startMovingTo(machinePos.getX() + 0.5, machinePos.getY(), machinePos.getZ() + 0.5, SPEED);
	}

	@Override
	public void stop() {
		machinePos = null;
		lastTryTime = villager.getWorld().getTime();
	}

	private BlockPos findNearestTicketMachine() {
		final BlockPos center = villager.getBlockPos();
		final BlockPos.Mutable mutable = new BlockPos.Mutable();
		BlockPos nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
			for (int dy = -4; dy <= 4; dy++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (isTicketMachine(mutable)) {
						final double distance = villager.squaredDistanceTo(mutable.getX() + 0.5, mutable.getY(), mutable.getZ() + 0.5);
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

	private boolean isTicketMachine(BlockPos pos) {
		// 用类名字符串判断，兼容 MTR 不同版本的包结构
		try {
			return villager.getWorld().getBlockState(pos).getBlock().getClass().getSimpleName().equals("BlockTicketMachine");
		} catch (Throwable e) {
			return false;
		}
	}
}
