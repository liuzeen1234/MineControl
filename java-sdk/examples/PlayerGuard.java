import dev.minecontrol.sdk.MineControl;

/** Watch loaded players within 10 blocks, teleport once, then exit. */
public class PlayerGuard {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 25580 : Integer.parseInt(args[0]);
        try (var mc = MineControl.connect(port)) {
            System.out.println("监测周围 10 格内的其他玩家（不含自己和旁观者），检测到后执行 /res tp liuhome 并结束。");
            while (!Thread.currentThread().isInterrupted()) {
                if (!mc.status().worldLoaded()) return;
                var players = mc.nearbyPlayers(10);
                if (!players.isEmpty()) {
                    System.out.println("发现玩家：" + players.get(0).name() + "，发送传送命令。");
                    mc.sendCommand("/res tp liuhome");
                    System.out.println("命令已发送，宏结束。传送是否成功由服务器决定。");
                    return;
                }
                mc.waitTicks(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
