package mtr.vilager.passenger.gua.event;

import mtr.vilager.passenger.gua.goal.BoardTrainGoal;
import mtr.vilager.passenger.gua.goal.RideTrainGoal;
import mtr.vilager.passenger.gua.goal.TicketMachineGoal;
import mtr.vilager.passenger.gua.mixin.MobEntityGoalAccessor;
import net.minecraft.entity.passive.VillagerEntity;

/** 由 ServerWorldMixin 在村民加入世界时调用 */
public class VillagerGoalAdder {
    public static void registerGoals(VillagerEntity villager) {
        // 将我们的 Goal 添加到村民的 goalSelector 中：过闸 3（先过闸进站），上车 4，售票机 5
        ((MobEntityGoalAccessor) villager).getGoalSelector().add(3, new RideTrainGoal(villager));
        ((MobEntityGoalAccessor) villager).getGoalSelector().add(4, new BoardTrainGoal(villager));
        ((MobEntityGoalAccessor) villager).getGoalSelector().add(5, new TicketMachineGoal(villager));
    }
}
