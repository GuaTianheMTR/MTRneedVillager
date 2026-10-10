package mtr.vilager.passenger.gua.helper;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;

public class MTRVillagerHelper {
    private static final String BALANCE_OBJECTIVE = "mtr_balance";

    public static void initVillagerBalance(VillagerEntity villager, ServerWorld world) {
        Scoreboard scoreboard = world.getScoreboard();
        ScoreboardObjective objective = scoreboard.getObjective(BALANCE_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(
                BALANCE_OBJECTIVE,
                ScoreboardCriterion.DUMMY,
                Text.literal("MTR Balance"),
                ScoreboardCriterion.RenderType.INTEGER
            );
        }
        // 设置为最大整数，即“无限”绿宝石
        scoreboard.getPlayerScore(villager.getUuidAsString(), objective).setScore(Integer.MAX_VALUE);
    }
}