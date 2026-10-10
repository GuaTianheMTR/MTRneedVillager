package mtr.vilager.passenger.gua.helper;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 村民乘车状态管理：
 * - 上车：Simulator.ride（MTR 官方乘客接口，玩家走 PacketUpdateVehicleRidingEntities，村民直接走模拟器）
 * - 每 tick 把村民的位置钉在列车车厢地板上（玩家是客户端做这件事，村民只能在服务端做）
 * - 列车再次停站开门（dwell）时自动下车：村民至少坐一站（列车发车离开过车站）才下车
 * - 下车时放到站台侧（记录上车方向），避免村民被屏蔽门困在轨道上
 */
public final class RidingVillagerManager {

	private static final Logger LOGGER = LoggerFactory.getLogger("mtrvilager");
	private static final double MAX_FOLLOW_DISTANCE = 256;
	private static final Map<UUID, RidingState> ridingVillagers = new HashMap<>();

	private RidingVillagerManager() {
	}

	public static boolean isRiding(UUID uuid) {
		return ridingVillagers.containsKey(uuid);
	}

	/** 让村民上车。sideX/sideZ 是村民上车的方向（站台侧），下车时按它反向放到站台上 */
	public static boolean tryBoard(VillagerEntity villager, long vehicleId, double sideX, double sideZ) {
		final Simulator simulator = MTRSimulatorBridge.getSimulator();
		if (simulator == null) {
			return false;
		}
		MTRSimulatorBridge.ride(simulator, villager.getUuid(), vehicleId);
		final double length = Math.sqrt(sideX * sideX + sideZ * sideZ);
		final double nx = length > 0.01 ? sideX / length : 1;
		final double nz = length > 0.01 ? sideZ / length : 0;
		ridingVillagers.put(villager.getUuid(), new RidingState(villager, vehicleId, nx, nz));
		LOGGER.info("村民 {} 坐上了列车 {}", villager.getUuid(), vehicleId);
		return true;
	}

	public static void onVillagerUnloaded(UUID uuid) {
		disembark(uuid);
	}

	public static void tick(ServerWorld world) {
		if (ridingVillagers.isEmpty()) {
			return;
		}
		final List<Vehicle> vehicles = MTRSimulatorBridge.getAllVehicles();
		final Iterator<Map.Entry<UUID, RidingState>> iterator = ridingVillagers.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<UUID, RidingState> entry = iterator.next();
			final RidingState state = entry.getValue();
			final VillagerEntity villager = state.villager;
			if (villager == null || !villager.isAlive() || villager.getWorld() != world || villager.isRemoved()) {
				// 村民没了
				iterator.remove();
				continue;
			}

			Vehicle vehicle = null;
			for (final Vehicle v : vehicles) {
				if (MTRSimulatorBridge.getVehicleId(v) == state.vehicleId) {
					vehicle = v;
					break;
				}
			}

			if (vehicle == null || villager.getBlockPos().getY() < world.getBottomY() + 1) {
				// 列车消失或村民被甩出世界：下车
				disembarkNow(villager, world, null, state);
				iterator.remove();
				continue;
			}

			// 钉在最近的车厢地板上
			final List<MTRSimulatorBridge.CarPosition> cars = MTRSimulatorBridge.getCarPositions(vehicle);
			if (cars.isEmpty()) {
				continue;
			}
			MTRSimulatorBridge.CarPosition best = cars.get(0);
			double bestDistance = Double.MAX_VALUE;
			for (final MTRSimulatorBridge.CarPosition car : cars) {
				final double distance = villager.squaredDistanceTo(car.x, car.y, car.z);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = car;
				}
			}

			if (MTRSimulatorBridge.isDwelling(vehicle) && state.hasDeparted) {
				// 列车再次到站开门：下车（刚上车时列车还在开门，不算，必须发车过）
				disembarkNow(villager, world, best, state);
				iterator.remove();
				continue;
			}
			if (MTRSimulatorBridge.isMoving(vehicle)) {
				// 列车发车（开过一站）：之后到站开门才算“坐够了”
				state.hasDeparted = true;
			}

			if (bestDistance > MAX_FOLLOW_DISTANCE * MAX_FOLLOW_DISTANCE) {
				// 列车开远了：下车
				disembarkNow(villager, world, best, state);
				iterator.remove();
				continue;
			}
			villager.setNoGravity(true);
			villager.updatePosition(best.x, best.y, best.z);
		}
	}

	/** 下车：恢复重力、从 MTR 移除乘客记录、把村民放到站台侧（而不是轨道中央） */
	private static void disembarkNow(VillagerEntity villager, ServerWorld world, MTRSimulatorBridge.CarPosition car, RidingState state) {
		villager.setNoGravity(false);
		MTRSimulatorBridge.stopRiding(MTRSimulatorBridge.getSimulator(), villager.getUuid());
		if (car != null) {
			placeOnPlatform(villager, world, car, state);
		}
	}

	/** 找站台侧可站立位置：优先上车方向，兜底四个方向；全部失败则原地 */
	private static void placeOnPlatform(VillagerEntity villager, ServerWorld world, MTRSimulatorBridge.CarPosition car, RidingState state) {
		final double y = car.y;
		if (state != null && (state.sideX != 0 || state.sideZ != 0)) {
			for (final double dist : new double[]{2.5, 3.5, 2.0, 4.0, 1.5}) {
				if (tryPlace(villager, world, car.x + state.sideX * dist, y, car.z + state.sideZ * dist)) {
					return;
				}
			}
		}
		for (final double dist : new double[]{2.5, 3.5, 2.0, 4.0}) {
			if (tryPlace(villager, world, car.x + dist, y, car.z)) {
				return;
			}
			if (tryPlace(villager, world, car.x - dist, y, car.z)) {
				return;
			}
			if (tryPlace(villager, world, car.x, y, car.z + dist)) {
				return;
			}
			if (tryPlace(villager, world, car.x, y, car.z - dist)) {
				return;
			}
		}
		villager.updatePosition(car.x, car.y, car.z);
	}

	/** 目标位置可安全站立（实心地面、脚/头是空气、不在轨道区）才放置 */
	private static boolean tryPlace(VillagerEntity villager, ServerWorld world, double x, double y, double z) {
		final BlockPos ground = BlockPos.ofFloored(x, y - 0.01, z);
		final BlockPos foot = BlockPos.ofFloored(x, y, z);
		final BlockPos head = BlockPos.ofFloored(x, y + 1, z);
		if (!world.getBlockState(ground).isOpaqueFullCube(world, ground)
				|| !world.getBlockState(foot).isAir()
				|| !world.getBlockState(head).isAir()) {
			return false;
		}
		if (nearAnyCar(x, z)) {
			return false;
		}
		villager.updatePosition(x + 0.5, y, z + 0.5);
		return true;
	}

	/** 是否落在任何一节车厢范围内（轨道区） */
	private static boolean nearAnyCar(double x, double z) {
		for (final Vehicle vehicle : MTRSimulatorBridge.getAllVehicles()) {
			for (final MTRSimulatorBridge.CarPosition car : MTRSimulatorBridge.getCarPositions(vehicle)) {
				final double dx = x - car.x;
				final double dz = z - car.z;
				if (dx * dx + dz * dz < 1.5 * 1.5) {
					return true;
				}
			}
		}
		return false;
	}

	private static void disembark(UUID uuid) {
		final RidingState state = ridingVillagers.remove(uuid);
		if (state != null && state.villager != null && state.villager.isAlive()) {
			state.villager.setNoGravity(false);
			MTRSimulatorBridge.stopRiding(MTRSimulatorBridge.getSimulator(), uuid);
		}
	}

	private static final class RidingState {
		private final VillagerEntity villager;
		private final long vehicleId;
		/** 列车是否已发车（至少坐过一站） */
		private boolean hasDeparted;
		/** 上车时的站台方向（单位向量），下车时反向放到站台 */
		private final double sideX;
		private final double sideZ;

		private RidingState(VillagerEntity villager, long vehicleId, double sideX, double sideZ) {
			this.villager = villager;
			this.vehicleId = vehicleId;
			this.sideX = sideX;
			this.sideZ = sideZ;
		}
	}
}
