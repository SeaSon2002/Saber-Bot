package ws.nmathe.saber.core;

import net.dv8tion.jda.api.AccountType;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.MiscUtil;
import net.dv8tion.jda.api.utils.SessionControllerAdapter;
import net.dv8tion.jda.api.utils.*;
import net.dv8tion.jda.api.requests.*;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import ws.nmathe.saber.Main;
import ws.nmathe.saber.utils.Logging;
import javax.security.auth.login.LoginException;
import java.util.*;
import java.util.concurrent.*;

/**
 * The ShardManager manages the JDA objects used to interface with the Discord api
 */
public class ShardManager
{
    private Integer shardTotal = null;                      // >0 sharding; =0 no sharding
    private ConcurrentMap<Integer, JDA> jdaShards = null;   // used only when sharded
    private JDA jda = null;                                 // used only when unsharded
    private JDABuilder builder;  // builder to be used as the template for starting/restarting shards

    /**
     * Populates the shard manager with initialized JDA shards (if sharding)
     * @param shards a list of integers, where each integer represents a shard ID
     *               The size of the list should never be greater than shardTotal
     * @param shardTotal the total number of shards to create
     */
    public ShardManager(List<Integer> shards, Integer shardTotal)
    {
        // initialize the list of 'Now Playing' games
        this.shardTotal = shardTotal;

        try // connect the bot to the discord API and initialize schedule components
        {
            // basic skeleton of a jda shard
            this.builder = JDABuilder.createLight(Main.getBotSettingsManager().getToken())
                    .setStatus(OnlineStatus.ONLINE)
                    .setChunkingFilter(ChunkingFilter.NONE)
                    //.disableIntents(GatewayIntent.GUILD_MEMBERS)
                    //.disableIntents(GatewayIntent.GUILD_BANS)
                    //.disableIntents(GatewayIntent.GUILD_EMOJIS)
                    //.disableIntents(GatewayIntent.GUILD_INVITES)
                    //.disableIntents(GatewayIntent.GUILD_VOICE_STATES)
                    //.disableIntents(GatewayIntent.GUILD_PRESENCES)
                    //.disableIntents(GatewayIntent.GUILD_MESSAGE_TYPING)
                    //.disableIntents(GatewayIntent.DIRECT_MESSAGE_TYPING)
                    //.enableIntents(GatewayIntent.GUILD_MESSAGES)
                    //.enableIntents(GatewayIntent.GUILD_MESSAGE_REACTIONS)
                    //.enableIntents(GatewayIntent.DIRECT_MESSAGES)
                    .setAutoReconnect(true);
            // set now playing status
            List<String> playing = Main.getBotSettingsManager().getNowPlayingList();
            if(!playing.isEmpty())
            {
                this.builder.setActivity(Activity.playing(playing.get(0)));
            }

            // EventListener handles all types of bot events
            this.builder.addEventListeners(new EventListener());

            // Disable parts of the cache
            builder.disableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE, CacheFlag.CLIENT_STATUS, CacheFlag.ACTIVITY);

            // previous session queue mechanism was deprecated and has seemingly been replaced with
            //   this SessionController object
            this.builder.setSessionController(new SessionControllerAdapter()
            {
                @Override
                public void appendSession(SessionConnectNode node)
                {
                    System.out.println("[SessionController] Adding SessionConnectNode to Queue!");
                    super.appendSession(node);
                }
            });

            // handle sharding
            if(shardTotal > 0)
            {
                this.jdaShards = new ConcurrentHashMap<>();
                Logging.info(this.getClass(), "Starting shard " + shards.get(0) + ". . .");

                // build the first shard synchronously with Main
                // to block the initialization process until one shard is active
                if(shards.contains(0))
                {
                    // build primary shard (id 0)
                    JDA jda = this.builder
                            .useSharding(0, shardTotal)
                            .build().awaitReady();

                    this.jdaShards.put(0, jda);
                    shards.remove((Object) 0);  // remove '0' (not necessarily the first element of the list)
                }
                else
                {
                    // build whatever the first shard id in the list is
                    // -this ought to occur only if the bot is running on multiple systems
                    // -and the current system is not responsible for the primary (0) shard
                    JDA jda = this.builder
                            .useSharding(shards.get(0), shardTotal)
                            .build().awaitReady();

                    this.jdaShards.put(shards.get(0), jda);
                    shards.remove(shards.get(0));
                }

                // core functionality can now be initialized
                Main.getEntryManager().init();
                Main.getCommandHandler().init();

                // start additional shards
                for (Integer shardId : shards)
                {
                    Logging.info(this.getClass(), "Starting shard " + shardId + ". . .");
                    JDA shard = this.builder
                            .useSharding(shardId, shardTotal)
                            .build().awaitReady();
                    this.jdaShards.put(shardId, shard);
                    for (Guild guild : shard.getGuilds())
                        Main.getCommandHandler().updateCommands(guild); // update per-shard necessary? I don't know
                }
            }
            else // no sharding
            {
                Logging.info(this.getClass(), "Starting bot without sharding. . .");
                this.jda = this.builder
                        .build().awaitReady();
                this.jda.setAutoReconnect(true);

                Main.getEntryManager().init();
                Main.getCommandHandler().init();
                for (Guild guild : jda.getGuilds())
                    Main.getCommandHandler().updateCommands(guild); // update per-shard necessary? I don't know
            }

            // executor service schedules shard-checking threads
            // restart any shards which are not in a CONNECTED state
            ScheduledExecutorService shardExecutor = Executors.newSingleThreadScheduledExecutor();
            shardExecutor.scheduleWithFixedDelay(() ->
            {
                Logging.info(this.getClass(), "Examining status of shards. . .");
                this.getShards().forEach((shard) ->
                {
                    JDA.Status status = shard.getStatus();
                    if (!status.equals(JDA.Status.CONNECTED))
                    {
                        Integer id = shard.getShardInfo().getShardId();
                        Logging.warn(this.getClass(), "Shard-"+id+" is not connected! ["+status+"]");

                        try
                        {
                            this.restartShard(id);
                        }
                        catch (LoginException | InterruptedException e)
                        {
                            Logging.warn(this.getClass(), "Failed to restart shard! ["+e.getMessage()+"]");
                        }
                        catch (Exception e)
                        {
                            Logging.exception(this.getClass(), e);
                            System.exit(-1);
                        }
                    }
                });
            }, 1, 1, TimeUnit.HOURS);
        }
        catch (Exception e)
        {
            Logging.exception(Main.class, e);
            System.exit(1);
        }
    }

    /**
     * Identifies if the bot is sharding enabled
     * @return bool
     */
    public boolean isSharding()
    {
        return jda == null;
    }

    /**
     * Retrieves the bot JDA if unsharded, otherwise take a shard from the shards collection
     * @return bot JDA
     */
    public JDA getJDA()
    {
        if(this.jda == null)
        {   // take a shard, any shard
            return this.jdaShards.values()
                    .iterator().next();
        }
        return this.jda;
    }

    /**
     * Retrieves the JDA responsible for a guild
     * @param guildId unique (snowflake) guild ID
     * @return JDA responsible for the guild
     */
    public JDA getJDA(String guildId)
    {
        return Main.getShardManager().isSharding() ?
                Main.getShardManager().getShard(guildId) : Main.getShardManager().getJDA();
    }

    /**
     * retrieves a specific JDA shard
     * Should only be used when sharding is enabled
     * @param shardId ID of JDA shard to retrieve
     * @return JDA shard
     */
    public JDA getShard(int shardId)
    {
        return jdaShards.get(shardId);
    }

    /**
     * Retrieves the shard responsible for a guild
     * Should only be used when sharding is enabled
     * @param guildId ID of guild
     * @return JDA shard
     */
    public JDA getShard(String guildId)
    {
        long id = MiscUtil.parseSnowflake(guildId);
        long shardId = (id >> 22) % Main.getBotSettingsManager().getShardTotal();
        return jdaShards.get((int) shardId);
    }


    /**
     * Retrieves all the JDA shards managed by this ShardManager
     * Should not be used when sharding is disabled
     * @return Collection of JDA Objects
     */
    public Collection<JDA> getShards()
    {
        if(this.isSharding())
        {
            return this.jdaShards.values();
        }
        else
        {
            return Collections.singletonList(this.jda);
        }
    }


    /**
     * Retrieves the full list of guilds attached to the application
     * Will not be accurate if the bot is sharded across multiple physical servers
     * @return List of Guild objects
     */
    public List<Guild> getGuilds()
    {
        if(jda == null)
        {
            List<Guild> guilds = new ArrayList<>();
            for(JDA jda : jdaShards.values())
            {
                guilds.addAll(jda.getGuilds());
            }
            return guilds;
        }

        return jda.getGuilds();
    }

    /**
     * Shuts down and recreates a JDA shard
     * @param shardId (Integer) shardID of the JDA shard
     */
    public void restartShard(Integer shardId) throws LoginException, InterruptedException {
        if (this.isSharding())
        {
            // do not handle shards not assigned to the current instance of the bot
            if (this.jdaShards.containsKey(shardId))
            {
                // shutdown the shard
                Logging.info(this.getClass(), "Shutting down shard-" + shardId + ". . .");
                this.getShard(shardId).shutdownNow();
                this.jdaShards.remove(shardId);

                // configure the builder from the template
                Logging.info(this.getClass(), "Starting shard-" + shardId + ". . .");
                JDABuilder shardBuilder;
                if (shardId == 0)
                {
                    shardBuilder = this.builder
                            //.setCorePoolSize(primaryPoolSize)
                            .useSharding(shardId, shardTotal);
                }
                else
                {
                    shardBuilder = this.builder
                            //.setCorePoolSize(secondaryPoolSize)
                            .useSharding(shardId, shardTotal);
                }

                // restart the shard (asynchronously)
                JDA shard = shardBuilder.build();
                this.jdaShards.put(shardId, shard);
            }
        }
        else
        {
            Logging.info(this.getClass(), "Restarting bot JDA. . .");
            this.jda.shutdownNow();
            this.jda = this.builder
                    //.setCorePoolSize(primaryPoolSize)
                    .build().awaitReady();
        }
    }
}
