package mtr.vilager.passenger.gua;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 所有行为由 mixin 驱动（不依赖 Fabric API）：
 * - ServerWorldMixin：世界 tick（乘车跟随）、村民加入（注册 AI）、村民离开（清理）
 * - PlayerManagerMixin：玩家进服时聊天栏弹出启动消息
 */
public class MTRneedvilager implements ModInitializer {
	public static final String MOD_ID = "mtrvilager";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[MTRneedVillager] 初始化开始...");
		LOGGER.info("MTRNV启动成功！");
	}
}
