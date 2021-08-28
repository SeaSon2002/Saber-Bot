package ws.nmathe.saber.core.schedule;

//import net.dv8tion.jda.client.events.relationship.GenericRelationshipAddEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.Message;
import ws.nmathe.saber.Main;
import ws.nmathe.saber.utils.ParsingUtilities;
import ws.nmathe.saber.utils.VerifyUtilities;

import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.time.*;
import java.util.List;


/**
 * This class is responsible for generating the event's message for display in the Discord client
 */
public class MessageGenerator
{
    private static String DEFAULT_URL = "https://youtu.be/dQw4w9WgXcQ";
    private static String ICON_URL = "https://upload.wikimedia.org/wikipedia/en/8/8d/Calendar_Icon.png";

    /**
     * Primary method which generates a complete Discord message object for the event
     * @param se (ScheduleEntry) to generate a message display
     * @return the message to be used to display the event in it's associated Discord channel
     */
    public static Message generate(ScheduleEntry se)
    {
        if (se == null) return new MessageBuilder().build();

        // prepare title
        String titleUrl = (se.getTitleUrl() != null && VerifyUtilities.verifyUrl(se.getTitleUrl())) ?
                se.getTitleUrl() : DEFAULT_URL;
        String titleImage = ICON_URL;

        // generate the footer
        String footerStr = generateFooter(se);

        // determine the embed color
        Color embedColor = generateColor(se);

        // generate the body of the embed
        String bodyContent;
        String style = Main.getScheduleManager().getStyle(se.getChannelId());
        if(style.equalsIgnoreCase("narrow"))
        {
            bodyContent = generateBodyNarrow(se);
        }
        else
        {
            bodyContent = generateBodyFull(se);
        }

        // prepare the embed
        EmbedBuilder builder = new EmbedBuilder();
        builder.setDescription(bodyContent.substring(0,Math.min(bodyContent.length(), 2048)))
                .setColor(embedColor)
                .setAuthor(se.getTitle(), titleUrl)//, titleImage)
                .setFooter(footerStr.substring(0,Math.min(footerStr.length(), 2048)), null);

        // add the image and thumbnail url links (if valid)
        if(se.getImageUrl() != null && VerifyUtilities.verifyUrl(se.getImageUrl()))
        {
            builder.setImage(se.getImageUrl());
        }
        if(se.getThumbnailUrl() != null && VerifyUtilities.verifyUrl(se.getThumbnailUrl()))
        {
            builder.setThumbnail(se.getThumbnailUrl());
        }

        MessageBuilder msgBuilder = new MessageBuilder().setEmbed(builder.build());
        if (se.getNonEmbededText() != null)
        {
            String fulltext = ParsingUtilities.processText(se.getNonEmbededText(), se, true);
            msgBuilder.setContent(fulltext);
        }

        // return the fully constructed message
        return msgBuilder.build();
    }


    /**
     * Generates the body content of the discord message for events using the
     * "full" display style
     * @param se the ScheduleEntry Object represented by the display
     * @return the body content as a string
     */
    private static String generateBodyFull(ScheduleEntry se)
    {
        StringBuilder msg = new StringBuilder();

        //
        // create the upper code block
        //
        String timeLines = generateTimeLines(se);
        String repeatLine = "> repeats " + se.getRecurrence().toString() + "\n";
        String expirationLine = generateExpirationLine(se);
        String locationLine = se.getLocation() == null ? "" : "<Location: " + se.getLocation() + ">\n";

        // append block lines
        msg.append("```Markdown\n\n")
                .append(timeLines)
                .append(repeatLine)
                .append(expirationLine)
                .append(locationLine)
                .append("```\n");

        //
        // insert the event description
        //
        msg.append(ParsingUtilities.processText(se.getDescription(), se, true))
                .append("\n");

        //
        // generate the lower code block
        //
        String timerLine = generateTimerLine(se);

        // if rsvp is enabled, show the number of rsvp
        StringBuilder rsvpLine = new StringBuilder();
        if (Main.getScheduleManager().isRSVPEnabled(se.getChannelId()))
        {
            rsvpLine.append("- ");
            Map<String, String> options = Main.getScheduleManager().getRSVPOptions(se.getChannelId());
            for (String emoji : options.keySet()) // I iterate over the keys rather than the values to keep a order consistent with reactions
            {
                String type = options.get(emoji);
                if (se.getRsvpLimit(type) != 0) // don't list the rsvp options on the event
                {
                    rsvpLine.append("<")
                            .append(type)
                            .append(" ")
                            .append(se.getRsvpMembersOfType(type).size())
                            .append(se.getRsvpLimit(type) >= 0 ? "/" + se.getRsvpLimit(type) + "> " : "> ");
                }
            }
            if (se.getDeadline() != null)
            {
                rsvpLine.append("\n+ RSVP closes ")
                        .append(se.getDeadline().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                        .append(" ").append(se.getDeadline().getDayOfMonth())
                        .append(", ")
                        .append(se.getDeadline().getYear())
                        .append(" @ ")
                        .append(se.getDeadline().toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                        .append(".");
            }

        }
        // append block lines
        msg.append("```Markdown\n\n")
                .append(timerLine)
                .append(rsvpLine)
                .append("```");

        // return full body string contents
        return msg.toString();
    }


    /**
     * Generates the body content of the discord message for events using the
     * "narrow" display style
     * @param se the ScheduleEntry Object represented by the display
     * @return the body content as a string
     */
    private static String generateBodyNarrow(ScheduleEntry se)
    {
        // create the first line of the body
        String timeLines = generateTimeLines(se);
        String newtimer = newtimer(se);
        // timezone and repeat information
        StringBuilder repeatLine = new StringBuilder()
                .append("[⏰]")
                .append(newtimer)
                .append("\n")
                .append("> ")
                .append(se.getRecurrence().toString())
                .append("\n");
        // expiration information
        String expirationLine = generateExpirationLine(se);

        // if rsvp is enabled, show the number of rsvps
        StringBuilder rsvpLine = new StringBuilder();
        if(Main.getScheduleManager().isRSVPEnabled(se.getChannelId()))
        {
            Map<String, String> options = Main.getScheduleManager().getRSVPOptions(se.getChannelId());
            // iterate over the keys rather than the values to keep
            // the order consistent with the order reactions are displayed
            for(String emoji : options.keySet())
            {
                String type = options.get(emoji);
                if (se.getRsvpLimit(type) != 0) // don't list the rsvp options on the event
                {
                    rsvpLine.append("<").append(type.charAt(0)).append(" ")
                            .append(se.getRsvpMembersOfType(type).size())
                            .append(se.getRsvpLimit(type) > 0 ? "/" + se.getRsvpLimit(type) + "> " : "> ");
                }
            }
        }
        return "```Markdown\n\n" + timeLines + repeatLine + expirationLine + rsvpLine + "```\n";
    }


    /**
     * @param se the ScheduleEntry
     * @return display line containing the expiration information
     */
    private static String generateExpirationLine(ScheduleEntry se)
    {
        StringBuilder repeatLine = new StringBuilder();
        if(se.getExpire() != null)
        {   // expire information
            repeatLine.append("> expires ")
                    .append(se.getExpire().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                    .append(" ")
                    .append(se.getExpire().getDayOfMonth())
                    .append(", ")
                    .append(se.getExpire().getYear())
                    .append("\n");
        }
        else if(se.getRecurrence().getCount() != null)
        {   // remaining event occurrences
            repeatLine.append("> occurs ")
                    .append(se.getRecurrence().countRemaining(se.getStart()))
                    .append(" more times\n");
        }
        return repeatLine.toString();
    }


    /**
     * @param se the ScheduleEntry
     * @return display lines containing the start/end information
     */
    private static String generateTimeLines(ScheduleEntry se)
    {
        StringBuilder timeLines = new StringBuilder();
        List<ZoneId> altZones = Main.getScheduleManager().getAltZones(se.getChannelId());
        if (!altZones.isEmpty())
        {
            altZones.add(se.getStart().getZone());  // add primary zone to list
            altZones.sort((zoneId, t1) -> {         // sort list by zone offset
                Instant now = Instant.now();
                return t1.getRules().getOffset(now)
                        .compareTo(zoneId.getRules().getOffset(now));
            });
            for (ZoneId zone : altZones)
            {
                timeLines.append(generateTimeLine(se, zone));
            }
        }
        else
        {
            timeLines.append(generateTimeLine(se, null));
        }
        return timeLines.toString();
    }

    /**
     * Generates the line of text which indicates the time the event begins and ends
     * Used by both generateBody...() methods
     * @param se the ScheduleEntry Object represented by the display
     * @return the body content as a string
     */
    private static String generateTimeLine(ScheduleEntry se, ZoneId zone)
    {
        String timeFormatter;
        if(Main.getScheduleManager().getClockFormat(se.getChannelId()).equals("24"))
            timeFormatter = "H:mm";
        else
            timeFormatter = "h:mm a";

        // adjust start and end if necessary
        ZonedDateTime start = (zone == null) ? se.getStart() : se.getStart().withZoneSameInstant(zone);
        ZonedDateTime end   = (zone == null) ? se.getEnd() : se.getEnd().withZoneSameInstant(zone);

        String dash = "\u2014";
        StringBuilder timeLine = new StringBuilder("< " + start.format(DateTimeFormatter.ofPattern("MMM d")));

        // event starts and ends at the same time
        if (start.until(end, ChronoUnit.SECONDS)==0)
        {
            timeLine.append(", ")
                    .append(start.format(DateTimeFormatter.ofPattern(timeFormatter)));
        }
        // time span is greater than 1 day
        else if (start.until(end, ChronoUnit.DAYS)>=1)
        {
            // all day events
            if (start.toLocalTime().equals(LocalTime.MIN)
                    && end.toLocalTime().equals(LocalTime.MIN))
            {
                timeLine.append(" ")
                        .append(dash)
                        .append(" ")
                        .append(end.format(DateTimeFormatter.ofPattern("MMM d")));
            }
            else // all other events
            {
                timeLine.append(", ")
                        .append(start.format(DateTimeFormatter.ofPattern(timeFormatter)))
                        .append(" ")
                        .append(dash)
                        .append(" ")
                        .append(end.format(DateTimeFormatter.ofPattern("MMM d")))
                        .append(", ")
                        .append(end.format(DateTimeFormatter.ofPattern(timeFormatter)));
            }
        }
        // time span is within 1 day
        else
        {
            timeLine.append(", ")
                    .append(start.format(DateTimeFormatter.ofPattern(timeFormatter)))
                    .append(" ")
                    .append(dash)
                    .append(" ")
                    .append(end.format(DateTimeFormatter.ofPattern(timeFormatter)));
        }
        timeLine.append(" > ");
        // add zone information
        if (zone != null)
        {
            timeLine.append("<")
                    .append(se.getStart().withZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("z")))
                    .append(">");
        }
        // append newline character & return full string
        timeLine.append("\n");
        return timeLine.toString();
    }


    /**
     * @param se the ScheduleEntry object
     * @return String representing the line containing the time until
     */
    private static String generateTimerLine(ScheduleEntry se)
    {
        StringBuilder line = new StringBuilder();
        List<ZoneId> altZones = Main.getScheduleManager().getAltZones(se.getChannelId());

        if (altZones.isEmpty())
        {
            line.append("[")
                    .append(se.getStart().getZone().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    .append("]");
            if (!se.hasStarted())
            {
                line.append("(begins ");
                genTimerHelper(se.getStart(), line, false, false);
                line.append(")");
            }
            else
            {
                line.append("(ends ");
                genTimerHelper(se.getEnd(), line, false, false);
                line.append(")");
            }
        }
        else
        {
            line.append("[");
            if (!se.hasStarted())
            {
                line.append("begins ");
                genTimerHelper(se.getStart(), line, false, false);
                line.append("](---");
            }
            else
            {
                line.append("in-progress](ends ");
                genTimerHelper(se.getEnd(), line, false, false);
            }
            line.append(")");
        }
        line.append("\n");
        return line.toString();
    }

    /**
     * used by generateTimeLine() to reduce code repetition
     * @param time the start or end time of the event
     * @param timer the string that should be built onto
     */
    private static void genTimerHelper(ZonedDateTime time, StringBuilder timer, boolean fineGrain, boolean useShort)
    {
        long timeTil = ZonedDateTime.now().until(time, ChronoUnit.SECONDS);
        if (fineGrain && timeTil < 60 * 60)
        {
            int minutesTil = (int)Math.ceil((double)timeTil/(60));
            if (minutesTil <= 1)
            {
                timer.append(useShort ? "<1m" : "sau 1 phút");
            }
            else
            {
                if (useShort)
                    timer.append(minutesTil)
                            .append("m");
                else
                    timer.append("sau ")
                            .append(minutesTil)
                            .append(" phút");
            }
        }
        else if (timeTil < 24 * 60 * 60)
        {
            int hoursTil = (int)Math.ceil((double)timeTil/(60*60));
            if (hoursTil <= 1)
            {
                timer.append(useShort ? "<1h" : "sau 1 giờ");
            }
            else
            {
                if (useShort)
                    timer.append(hoursTil)
                            .append("h");
                else
                    timer.append("sau ")
                            .append(hoursTil)
                            .append(" giờ");
            }
        }
        else
        {
            int daysTil = (int) ChronoUnit.DAYS.between(
                    ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS),
                    time.truncatedTo(ChronoUnit.DAYS));
            if (daysTil <= 1)
            {
                timer.append(useShort ? "<"+daysTil+"d" : "sau 1 ngày");
            }
            else
            {
                if (useShort)
                    timer.append(daysTil)
                            .append("d");
                else
                    timer.append("sau ").append(daysTil).append(" ngày");
            }
        }
    }


    /**
     * creates the footer text to be added to the message embed
     * @param se ScheduleEntry object
     * @return fully generated footer String
     */
    private static String generateFooter(ScheduleEntry se)
    {
        // initialize footer with ID information
        StringBuilder footerStr = new StringBuilder("ID: " + ParsingUtilities.intToEncodedID(se.getId()));

        // append quiet information to footer
        if(se.isQuietEnd() || se.isQuietStart() || se.isQuietRemind())
        {
            footerStr.append(" |");
            if(se.isQuietStart()) footerStr.append(" quiet-start");
            if(se.isQuietEnd()) footerStr.append(" quiet-end");
            if(se.isQuietRemind()) footerStr.append(" quiet-remind");
        }

        // generate reminder footer
        List<Date> reminders = new ArrayList<>();
        reminders.addAll(se.getReminders());
        reminders.addAll(se.getEndReminders());
        if (!reminders.isEmpty())
        {
            footerStr.append(" | remind in ");
            for (int i=0; i<reminders.size(); i++)
            {
                ZonedDateTime time = ZonedDateTime.ofInstant(reminders.get(i).toInstant(), ZoneId.systemDefault());
                genTimerHelper(time, footerStr, true, true);
                if (i != reminders.size()-1)
                    if (reminders.size() > 2)
                        footerStr.append(", ");
                else if (reminders.size() > 1)
                    footerStr.append(" and ");

            }
        }

        // return completed footer
        return footerStr.toString();
    }

    /**
     * creates the color object to be used with the embed
     * @param se ScheduleEntry object
     * @return color
     */
    private static Color generateColor(ScheduleEntry se)
    {
        // attempt to use the ScheduleEntry's color attribute
        Color color = null;
        if (se.getColor() != null)
        {
            color = Color.getColor(se.getColor());
            if (color == null)
            {
                try
                {
                    color = Color.decode(se.getColor());
                }
                catch (NumberFormatException ignored)
                {
                    color = null;
                }
            }
        }

        // if color not yet defined, use color from bot hoisted role
        if(color == null)
        {
            // set default
            color = Color.DARK_GRAY;

            // find JDA shard instance for guild
            JDA jda = Main.getShardManager().getJDA(se.getGuildId());

            // get embed color from first hoisted bot role
            List<Role> roles = new ArrayList<>(
                    jda.getGuildById(se.getGuildId())
                            .getMember(jda.getSelfUser())
                            .getRoles());
            while(!roles.isEmpty())
            {
                if(roles.get(0).isHoisted())
                {
                    color = roles.get(0).getColor();
                    break;
                }
                else
                {
                    roles.remove(0);
                }
            }
        }

        // return the color (default DARK_GRAY)
        return color;
    }

        private static String newtimer(ScheduleEntry se)
    {
        StringBuilder line = new StringBuilder();
                line.append("[Bắt đầu ");
                genTimerHelper(se.getStart(), line, true, false);
                line.append("]");
                return line.toString();
    }
}
