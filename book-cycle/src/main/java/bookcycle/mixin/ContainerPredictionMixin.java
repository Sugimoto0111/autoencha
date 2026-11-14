package bookcycle.mixin;

import bookcycle.BookCycle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
abstract class ContainerPredictionMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void bookCycle$predictClick(int slot, int button, ContainerInput input, Player player, CallbackInfo info) {
        if (player instanceof LocalPlayer && BookCycle.isSendingClick()) info.cancel();
    }
}
