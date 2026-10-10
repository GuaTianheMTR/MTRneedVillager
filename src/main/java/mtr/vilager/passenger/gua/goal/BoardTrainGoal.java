package mtr.vilager.passenger.gua.goal;

import mtr.vilager.passenger.gua.helper.MTRSimulatorBridge;
import mtr.vilager.passenger.gua.helper.RidingVillagerManager;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 村民"有时"自主上车：走到站台边停靠开门的列车旁，然后乘上（Simulator.ride）。
 * 上车后的位置跟随与到站下车由 RidingVillagerManager 负责。
 */
public class BoardTrainGoal extends Goal {

	private static final Logger LOGGER = LoggerFactory.getLogger("mtrvilager");
	private static final int SEARCH_RADIUS = 24;
	private static final int COOLDOWN_TICKS = 200;
	private static final int TRIGGER_RANDOM_BOUND = 1200;
	private static final int GIVE_UP_TICKS = 600;
	private static final double BOARD_DISTANCE = 4.0;
	private static final double SPEED = 0.5;

	private final VillagerEntity villager;
	private long targetVehicleId;
	private MTRSimulatorBridge.CarPosition target;
	private long lastTryTime;
	private int runningTicks;

	public BoardTrainGoal(VillagerEntity villager) {
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
		// "偶尔"：平均约每 1 分钟随机触发一次
		if (villager.getRandom().nextInt(TRIGGER_RANDOM_BOUND) != 0) {
			return false;
		}
		final BoardableTrain boardable = findBoardableTrain();
		if (boardable == null) {
			return false;
		}
		targetVehicleId = boardable.vehicleId;
		target = boardable.position;
		LOGGER.info("村民 {} 决定走向列车 {}（车厢 {} {} {}）", villager.getUuid(), targetVehicleId, target.x, target.y, target.z);
		return true;
	}

	@Override
	public void start() {
		runningTicks = 0;
	}

	@Override
	public boolean shouldContinue() {
		if (++runningTicks > GIVE_UP_TICKS) {
			return false;
		}
		if (target == null) {
			return false;
		}
		final Vehicle vehicle = findVehicle(targetVehicleId);
		return vehicle != null && MTRSimulatorBridge.isOnRoute(vehicle) && !MTRSimulatorBridge.isMoving(vehicle);
	}

	@Override
	public void tick() {
		if (target == null) {
			return;
		}
		final Vehicle vehicle = findVehicle(targetVehicleId);
		if (vehicle == null || !MTRSimulatorBridge.isOnRoute(vehicle) || MTRSimulatorBridge.isMoving(vehicle)) {
			// 列车关门或开走了，放弃
			return;
		}
		// 刷新目标位置（列车停站位置固定，刷新以贴合最新数据）
		final MTRSimulatorBridge.CarPosition car = findNearestCar(vehicle);
		if (car != null) {
			// 目标点取“站台上的可站立点”：村民始终站在站台上等车，绝不走向轨道（防穿屏蔽门/卡轨道）
			final MTRSimulatorBridge.CarPosition side = stationSidePoint(vehicle, car);
			if (side == null) {
				target = null;
				villager.getNavigation().stop();
				return;
			}
			target = side;
		}
		final double dx = villager.getX() - target.x;
		final double dz = villager.getZ() - target.z;
		if (dx * dx + dz * dz < BOARD_DISTANCE * BOARD_DISTANCE) {
			// 已到站台边：直接上车（Simulator.ride 是记录，不需要列车开门）
			villager.getNavigation().stop();
			final double sideX = car != null ? target.x - car.x : 1;
			final double sideZ = car != null ? target.z - car.z : 0;
			if (RidingVillagerManager.tryBoard(villager, targetVehicleId, sideX, sideZ)) {
				runningTicks = GIVE_UP_TICKS + 1;
			}
			return;
		}
		villager.getNavigation().startMovingTo(target.x, target.y - 0.5, target.z, SPEED);
	}

	@Override
	public void stop() {
		target = null;
		lastTryTime = villager.getWorld().getTime();
	}

	/** 在车厢附近找站台可站立点：沿村民所在方向（轴对齐）扫描，找不到返回 null（放弃，绝不走向轨道） */
	private MTRSimulatorBridge.CarPosition stationSidePoint(Vehicle vehicle, MTRSimulatorBridge.CarPosition car) {
		int dirX = (int) Math.signum(villager.getX() - car.x);
		int dirZ = (int) Math.signum(villager.getZ() - car.z);
		if (dirX == 0 && dirZ == 0) {
			dirX = 1;
		}
		for (final double dist : new double[]{2.0, 2.5, 3.0, 1.5, 3.5}) {
			final double x = car.x + dirX * dist;
			final double z = car.z + dirZ * dist;
			if (isStandable(x, car.y, z) && !nearCar(vehicle, x, z)) {
				return new MTRSimulatorBridge.CarPosition(x, car.y, z, car.bogie1, car.bogie2);
			}
		}
		return null;
	}

	/** 目标点可站立：脚下方块实心（站台面），脚与头两格都是空气 */
	private boolean isStandable(double x, double y, double z) {
		final ServerWorld world = (ServerWorld) villager.getWorld();
		final BlockPos ground = BlockPos.ofFloored(x, y - 0.01, z);
		final BlockPos foot = BlockPos.ofFloored(x, y, z);
		final BlockPos head = BlockPos.ofFloored(x, y + 1, z);
		return world.getBlockState(ground).isOpaqueFullCube(world, ground)
				&& world.getBlockState(foot).isAir()
				&& world.getBlockState(head).isAir();
	}

	/** 是否落在车厢范围内（轨道区），防止把村民目标放在轨道上 */
	private boolean nearCar(Vehicle vehicle, double x, double z) {
		for (final MTRSimulatorBridge.CarPosition car : MTRSimulatorBridge.getCarPositions(vehicle)) {
			final double dx = x - car.x;
			final double dz = z - car.z;
			if (dx * dx + dz * dz < 1.5 * 1.5) {
				return true;
			}
		}
		return false;
	}

	private Vehicle findVehicle(long vehicleId) {
		for (final Vehicle vehicle : MTRSimulatorBridge.getAllVehicles()) {
			if (MTRSimulatorBridge.getVehicleId(vehicle) == vehicleId) {
				return vehicle;
			}
		}
		return null;
	}

	private MTRSimulatorBridge.CarPosition findNearestCar(Vehicle vehicle) {
		final List<MTRSimulatorBridge.CarPosition> cars = MTRSimulatorBridge.getCarPositions(vehicle);
		MTRSimulatorBridge.CarPosition best = null;
		double bestDistance = Double.MAX_VALUE;
		for (final MTRSimulatorBridge.CarPosition car : cars) {
			final double distance = villager.squaredDistanceTo(car.x, car.y - 0.5, car.z);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = car;
			}
		}
		return best;
	}

	private BoardableTrain findBoardableTrain() {
		// 第一轮：在线路上停靠的车（正常运营）；第二轮：任何停着的车（车库/未运营也能上）
		BoardableTrain fallback = null;
		for (final Vehicle vehicle : MTRSimulatorBridge.getAllVehicles()) {
			if (MTRSimulatorBridge.isMoving(vehicle)) {
				continue;
			}
			final List<MTRSimulatorBridge.CarPosition> cars = MTRSimulatorBridge.getCarPositions(vehicle);
			for (final MTRSimulatorBridge.CarPosition car : cars) {
				final double distance = villager.squaredDistanceTo(car.x, car.y - 0.5, car.z);
				if (distance < SEARCH_RADIUS * SEARCH_RADIUS) {
					if (MTRSimulatorBridge.isOnRoute(vehicle)) {
						return new BoardableTrain(MTRSimulatorBridge.getVehicleId(vehicle), car);
					}
					if (fallback == null) {
						fallback = new BoardableTrain(MTRSimulatorBridge.getVehicleId(vehicle), car);
					}
				}
			}
		}
		return fallback;
	}

	private static final class BoardableTrain {
		private final long vehicleId;
		private final MTRSimulatorBridge.CarPosition position;

		private BoardableTrain(long vehicleId, MTRSimulatorBridge.CarPosition position) {
			this.vehicleId = vehicleId;
			this.position = position;
		}
	}
}
