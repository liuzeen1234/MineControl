package dev.minecontrol.audit;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

/** Local test instrumentation only. Observes server events; does not classify cheats. */
public final class TestAudit extends JavaPlugin implements Listener {
 private BufferedWriter out;
 private long seq;
 public void onEnable() {
  try { getDataFolder().mkdirs(); out=Files.newBufferedWriter(getDataFolder().toPath().resolve("actions.jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
  catch(IOException e) { throw new IllegalStateException(e); }
  Bukkit.getPluginManager().registerEvents(this,this);
  Bukkit.getScheduler().runTaskTimer(this,()->{try{out.flush();}catch(IOException e){getLogger().severe(e.toString());}},20,20);
  getLogger().info("LOCAL TEST: server-event audit enabled; cancellable kick events will be blocked and logged. No anti-cheat classification is performed.");
 }
 public void onDisable(){try{if(out!=null)out.close();}catch(IOException e){getLogger().severe(e.toString());}}
 private String q(String x){return "\""+x.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
 private void log(Player p,String event,String detail){
  var l=p.getLocation();
  try {out.write("{\"seq\":"+(++seq)+",\"time\":"+q(Instant.now().toString())+",\"nano\":"+System.nanoTime()+",\"tick\":"+Bukkit.getCurrentTick()+",\"player\":"+q(p.getName())+",\"event\":"+q(event)+",\"detail\":"+q(detail)+",\"x\":"+l.getX()+",\"y\":"+l.getY()+",\"z\":"+l.getZ()+",\"yaw\":"+l.getYaw()+",\"pitch\":"+l.getPitch()+"}\n");}
  catch(IOException e){getLogger().severe(e.toString());}
 }
 @EventHandler(priority=EventPriority.MONITOR) public void swing(PlayerAnimationEvent e){log(e.getPlayer(),"swing",e.getAnimationType().name());}
 @EventHandler(priority=EventPriority.MONITOR) public void interact(PlayerInteractEvent e){log(e.getPlayer(),"interact",e.getAction()+" "+e.getHand()+" "+e.getClickedBlock()+" cancelled="+e.isCancelled());}
 @EventHandler(priority=EventPriority.MONITOR) public void damage(BlockDamageEvent e){log(e.getPlayer(),"dig_start",e.getBlock()+" cancelled="+e.isCancelled());}
 @EventHandler(priority=EventPriority.MONITOR) public void abort(BlockDamageAbortEvent e){log(e.getPlayer(),"dig_abort",e.getBlock().toString());}
 @EventHandler(priority=EventPriority.MONITOR) public void broken(BlockBreakEvent e){log(e.getPlayer(),"block_break",e.getBlock()+" cancelled="+e.isCancelled());}
 @EventHandler(priority=EventPriority.MONITOR) public void place(BlockPlaceEvent e){log(e.getPlayer(),"block_place",e.getBlock()+" cancelled="+e.isCancelled());}
 @EventHandler(priority=EventPriority.MONITOR) public void inv(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p)log(p,"inventory_click",e.getView().getType()+" slot="+e.getRawSlot()+" click="+e.getClick()+" item="+e.getCurrentItem());}
 @EventHandler(priority=EventPriority.MONITOR) public void item(PlayerItemHeldEvent e){log(e.getPlayer(),"held_slot",e.getPreviousSlot()+"->"+e.getNewSlot());}
 @EventHandler(priority=EventPriority.MONITOR) public void move(PlayerMoveEvent e){log(e.getPlayer(),"move","");}
 @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){log(e.getPlayer(),"join","");}
 @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){log(e.getPlayer(),"quit",e.getReason().name());}
 @EventHandler(priority=EventPriority.HIGHEST) public void kick(PlayerKickEvent e){log(e.getPlayer(),"kick_blocked",e.getCause()+" "+e.getReason());e.setCancelled(true);getLogger().warning("LOG ONLY: blocked kick for "+e.getPlayer().getName()+": "+e.getCause()+" "+e.getReason());}
}
