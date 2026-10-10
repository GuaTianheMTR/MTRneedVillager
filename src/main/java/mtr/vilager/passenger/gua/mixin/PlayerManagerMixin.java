package mtr.vilager.passenger.gua.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家进服时在聊天栏弹出启动消息 */
@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

	@Inject(method = "onPlayerConnect", at = @At("RETURN"))
	private void mtrvilager$onPlayerJoin(ServerPlayerEntity player, CallbackInfo ci) {
		if (player != null) {
			player.sendMessage(Text.literal("[MTRneedVillager] MTRNV启动成功！").formatted(Formatting.GREEN), false);
		}
	}
}
