package dk.dma.msiproxy.provider.dkmsi.service;

import dk.dma.msiproxy.common.conf.TextResource;
import dk.dma.msiproxy.common.provider.AbstractProviderService;
import dk.dma.msiproxy.common.provider.MessageCache;
import dk.dma.msiproxy.common.provider.Providers;
import dk.dma.msiproxy.common.repo.RepositoryService;
import dk.dma.msiproxy.common.util.TextUtils;
import dk.dma.msiproxy.common.util.TimeUtils;
import dk.dma.msiproxy.model.msi.Area;
import dk.dma.msiproxy.model.msi.Category;
import dk.dma.msiproxy.model.msi.Chart;
import dk.dma.msiproxy.model.msi.Location;
import dk.dma.msiproxy.model.msi.LocationType;
import dk.dma.msiproxy.model.msi.Message;
import dk.dma.msiproxy.model.msi.Point;
import dk.dma.msiproxy.model.msi.SeriesIdType;
import dk.dma.msiproxy.model.msi.SeriesIdentifier;
import dk.dma.msiproxy.model.msi.Status;
import dk.dma.msiproxy.model.msi.Type;
import dk.dma.msiproxy.provider.dkmsi.conf.DkMsiDB;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides a business interface for accessing Danish legacy MSI messages.
 * The resulting MSI list will be composed from two types of legacy messages:
 * <ul>
 *     <li>MSI: The legacy MSI message</li>
 *     <li>Firing Exercises: The legacy firing exercises</li>
 * </ul>
 *
 * Both types of data are read in from the legacy MSI-editor database, or rather,
 * an export of selected tables from the legacy database. The relevant export is
 * created thus:
 * <pre>
 *     mysqldump -u DB_USER --password=DB_PWD DB_NAME \
 *     message priority msg_class msg_category msg_sub_category location locationtype main_area country point \
 *     firing_period, firing_area, firing_area_information, information, information_type, firing_area_position \
 *     | gzip -9 > oldmsi_backup.sql.gz
 * </pre>
 */
@Singleton
@Lock(LockType.READ)
@Startup
public class DkMsiProviderService extends AbstractProviderService {

    public static final String PROVIDER_ID = "dkmsi";
    public static final int PRIORITY = 200;
    public static final String[] LANGUAGES = { "da", "en" };

    Pattern CHART_PATTERN_1 = Pattern.compile("(\\d+)");
    Pattern CHART_PATTERN_2 = Pattern.compile("(\\d+) \\(INT (\\d+)\\)");

    @Inject
    Logger log;

    @Inject
    Providers providers;

    @Inject
    MessageCache messageCache;

    @Inject
    RepositoryService repositoryService;

    @Inject
    @DkMsiDB
    EntityManager em;

    @Inject
    @TextResource("/sql/active_msi_and_firing_exercises.sql")
    private String activeMessagesSql;

    @Inject
    @TextResource("/sql/msi_message_data.sql")
    private String msiMessageDataSql;

    @Inject
    @TextResource("/sql/msi_location_data.sql")
    private String msiLocationDataSql;

    @Inject
    @TextResource("/sql/firing_exercise_message_data.sql")
    private String firingExerciseMessageDataSql;

    @Inject
    @TextResource("/sql/firing_exercise_location_data.sql")
    private String firingExerciseLocationDataSql;

    /**
     * {@inheritDoc}
     */
    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MessageCache getMessageCache() {
        return messageCache;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RepositoryService getRepositoryService() {
        return repositoryService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getPriority() {
        return PRIORITY;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String[] getLanguages() {
        return LANGUAGES;
    }

    /**
     * Called at start up.
     */
    @PostConstruct
    public void init() {
        // Register with the providers service
        providers.registerProvider(this);

        // Load messages
        loadMessages();
    }

    /**
     * Called every 5 minutes to update message list
     */
    @Schedule(persistent = false, second = "38", minute = "*/5", hour = "*", dayOfWeek = "*", year = "*")
    protected void loadMessagesPeriodically() {
        loadMessages();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Message> loadMessages() {

        long t0 = System.currentTimeMillis();
        try {

            // Load the list of active legacy MSI and firing exercises
            List<ActiveMessage> activeMessages = readActiveMessages();

            // Check if there are any changes to the current list of messages
            if (isMessageListUnchanged(activeMessages)) {
                log.info("Legacy MSI messages not changed");
                return messages;
            }

            // Read the message details from the DB one by one
            List<Message> result = new ArrayList<>();
            activeMessages.forEach(msg -> {
                Message message = (msg.isMsi()) ? readMsiMessage(msg.getId()) : readFiringExerciseMessage(msg.getId());
                if (message != null) {
                    result.add(message);
                }
            });

            log.info(String.format("Loaded %d legacy MSI messages in %d ms", result.size(), System.currentTimeMillis() - t0));
            setActiveMessages(result);

        } catch (Exception e) {
            log.error("Failed loading legacy MSI messages: " + e.getMessage(), e);
        }

        return messages;
    }

    /**
     * Reads the list of active legacy MSI and firing exercises
     *
     * @return the list of active legacy MSI and firing exercises
     */
    private List<ActiveMessage> readActiveMessages() {
        @SuppressWarnings("unchecked")
        List<Object[]> activeMessages = em
                .createNativeQuery(activeMessagesSql)
                .getResultList();

        return activeMessages.stream()
                .map(ActiveMessage::new)
                .collect(Collectors.toList());
    }

    /**
     * Checks if the currently list of messages is unchanged from the active messages
     *
     * @param activeMessages the active messages to compare the current list of messages to
     * @return if the currently list of messages is unchanged from the active messages
     */
    private boolean isMessageListUnchanged(List<ActiveMessage> activeMessages) {
        if (activeMessages.size() == messages.size()) {

            // Check that the message ids and change dates of the two lists are identical
            for (int x = 0; x < messages.size(); x++) {
                Message msg = messages.get(x);
                ActiveMessage activeMsg = activeMessages.get(x);
                if (!activeMsg.isUnchanged(msg)) {
                    return false;
                }
            }
            // No changes
            return true;
        }

        return false;
    }

    /**
     * Reads the legacy MSI data for the given message.
     *
     * If a message is associated with a location containing multiple points, there will
     * be one row of per point. Otherwise, there will be only one row for the message.
     *
     * @param id the ID of the MSI to read in
     * @return the resulting messages
     */
    @SuppressWarnings("unused")
    private Message readMsiMessage(Integer id) {
        // First read the core MSI data
        Message message = readMsiMessageData(id);

        // Read the location data
        if (message != null) {
            message = readMsiLocationData(message);
        }
        return message;
    }

    /**
     * Reads the legacy MSI data for the given message.
     *
     * @param id the ID of the MSI to read in
     * @return the resulting message
     */
    @SuppressWarnings("unused")
    private Message readMsiMessageData(Integer id) {

        // Inject the id into the SQL
        String sql = msiMessageDataSql.replace(":id", id.toString());

        // Execute the DB query
        @SuppressWarnings("unchecked")
        List<Object[]> msiData = em.createNativeQuery(sql)
                .getResultList();

        if (msiData.size() == 0) {
            // Should never happen...
            return null;
        }

        Object[] row = msiData.get(0);
        int col = 0;
        Integer messageId           = getInt(row, col++);
        Boolean statusDraft         = getBoolean(row, col++);
        String  navtexNo            = getString(row, col++);
        String  descriptionEn       = getString(row, col++);
        String  descriptionDa       = getString(row, col++);
        String  title               = getString(row, col++);
        Date    validFrom           = getDate(row, col++);
        Date    validTo             = getDate(row, col++);
        Date    created             = getDate(row, col++);
        Date    updated             = getDate(row, col++);
        Date    deleted             = getDate(row, col++);
        Integer version             = getInt(row, col++);
        String  priority            = getString(row, col++);
        String  messageType         = getString(row, col++);
        Integer category1Id         = getInt(row, col++);
        String  category1En         = getString(row, col++);
        String  category1Da         = getString(row, col++);
        Integer category2Id         = getInt(row, col++);
        String  category2En         = getString(row, col++);
        String  category2Da         = getString(row, col++);
        Integer area1Id             = getInt(row, col++);
        String  area1En             = getString(row, col++);
        String  area1Da             = getString(row, col++);
        Integer area2Id             = getInt(row, col++);
        String  area2En             = getString(row, col++);
        String  area2Da             = getString(row, col++);
        String  area3En             = getString(row, col++);
        String  area3Da             = getString(row, col++);
        String  locationType        = getString(row, col);

        Message message = new Message();

        message.setId(id);
        message.setCreated(created);
        message.setUpdated(updated);
        message.setVersion(version);
        message.setValidFrom(validFrom);
        message.setValidTo(validTo);

        SeriesIdentifier identifier = new SeriesIdentifier();
        message.setSeriesIdentifier(identifier);
        identifier.setMainType(SeriesIdType.MSI);
        if (StringUtils.isNotBlank(navtexNo) && navtexNo.split("-").length == 3) {
            // Extract the series identifier from the navtext number
            String[] parts = navtexNo.split("-");
            identifier.setAuthority(parts[0]);
            identifier.setNumber(Integer.valueOf(parts[1]));
            identifier.setYear(2000 + Integer.valueOf(parts[2]));

        } else {
            // Some legacy MSI do not have a navtex number.
            Calendar cal = Calendar.getInstance();
            cal.setTime(validFrom);
            int year = cal.get(Calendar.YEAR);
            identifier.setAuthority("DK");
            identifier.setYear(year);
        }

        if ("Navtex".equals(messageType) || "Navwarning".equals(messageType)) {
            message.setType(Type.SUBAREA_WARNING);
        } else {
            message.setType(Type.COASTAL_WARNING);
        }

        // We only fetch published (active) messages
        message.setStatus(Status.PUBLISHED);

        // Message Desc
        if (StringUtils.isNotBlank(title) || StringUtils.isNotBlank(descriptionEn) || StringUtils.isNotBlank(area3En)) {
            Message.MessageDesc descEn = message.checkCreateDesc("en");
            descEn.setTitle(StringUtils.defaultString(title, descriptionEn));
            descEn.setDescription(TextUtils.txt2html(descriptionEn));
            descEn.setVicinity(area3En);
        }
        if (StringUtils.isNotBlank(title) || StringUtils.isNotBlank(descriptionDa) || StringUtils.isNotBlank(area3Da)) {
            Message.MessageDesc descDa = message.checkCreateDesc("da");
            descDa.setTitle(StringUtils.defaultString(title, descriptionDa));
            descDa.setDescription(TextUtils.txt2html(descriptionDa));
            descDa.setVicinity(area3Da);
        }

        // Areas
        Area area = createAreaTemplate(area1Id, area1En, area1Da, null);
        // Annoyingly, legacy data has Danmark as a sub-area of Danmark
        if (!StringUtils.equals(area1En, area2En) || !StringUtils.equals(area1Da, area2Da)) {
            area = createAreaTemplate(area2Id, area2En, area2Da, area);
        }
        message.setArea(area);

        // Categories
        // NB: The category structure is not very usable and will be changed for MSI-NM
         Category category = createCategoryTemplate(category1Id, category1En, category1Da, null);
         category = createCategoryTemplate(category2Id, category2En, category2Da, category);
         if (category != null) {
            message.checkCreateCategories().add(category);
         }


        // Read the location type
        Location location = new Location();
        message.checkCreateLocations().add(location);
        switch (locationType) {
            case "Point": location.setType(LocationType.POINT); break;
            case "Points": location.setType(LocationType.POINT); break;
            case "Polygon": location.setType(LocationType.POLYGON); break;
            case "Polyline": location.setType(LocationType.POLYLINE); break;
            default: location.setType(LocationType.POLYLINE);
        }

        return message;
    }

    /**
     * Reads the location for the legacy MSI message.
     *
     * @param message the message to read the location for
     * @return the updated message
     */
    private Message readMsiLocationData(Message message) {

        // Inject the id into the SQL
        String sql = msiLocationDataSql.replace(":id", message.getId().toString());

        // Execute the DB query
        @SuppressWarnings("unchecked")
        List<Object[]> msiData = em.createNativeQuery(sql)
                .getResultList();

        // If there are no points, remove the location
        if (msiData.size() == 0) {
            message.setLocations(null);
            return message;
        }

        // Add the points to the message location
        Location location = message.getLocations().get(0);
        for (Object[] row : msiData) {

            // Read the location point data from the DB
            int col = 0;
            Integer pointIndex      = getInt(row, col++);
            Double pointLatitude    = getDouble(row, col++);
            Double pointLongitude   = getDouble(row, col++);
            Integer pointRadius     = getInt(row, col);

            // If the type of the location is POINT, there must only be one point per location
            if (location.getType() == LocationType.POINT && location.checkCreatePoints().size() > 0) {
                location = new Location();
                location.setType(LocationType.POINT);
                message.getLocations().add(location);
            }

            location.setRadius(pointRadius);

            // Create the current point
            Point pt = new Point();
            pt.setIndex(pointIndex);
            pt.setLat(pointLatitude);
            pt.setLon(pointLongitude);
            location.checkCreatePoints().add(pt);
        }

        // Check the location to make it valid
        if (message.getLocations() != null && message.getLocations().size() > 0) {
            Location loc = message.getLocations().get(0);
            if (loc != null && loc.getType() == LocationType.POLYGON && loc.getPoints().size() < 3) {
                loc.setType(LocationType.POLYLINE);
            }
            if (loc != null && loc.getType() == LocationType.POLYLINE && loc.getPoints().size() < 2) {
                loc.setType(LocationType.POINT);
            }
        }

        return message;
    }

    /**
     * Reads the firing exercises for the given ID
     * @param id the id of the firing exercise to read in
     * @return the firing exercise
     */
    private Message readFiringExerciseMessage(Integer id) {
        // First read the core MSI data
        Message message = readFiringExerciseMessageData(id);

        // Read the location data
        if (message != null) {
            message = readFiringExerciseLocationData(message);
        }
        return message;
    }

    /**
     * Reads the legacy firing exercise data for the given message.
     *
     * @param id the ID of the firing exercise to read in
     * @return the resulting message
     */
    private Message readFiringExerciseMessageData(Integer id) {
        // Inject the id into the SQL
        String sql = firingExerciseMessageDataSql.replace(":id", id.toString());

        // Execute the DB query
        @SuppressWarnings("unchecked")
        List<Object[]> feData = em.createNativeQuery(sql)
                .getResultList();

        if (feData.size() == 0) {
            // Should never happen...
            return null;
        }

        Message message = null;
        for (Object[] row : feData) {

            int col = 0;
            Date    created             = getDate(row, col++);
            Date    updated             = getDate(row, col++);
            Date    validFrom           = getDate(row, col++);
            Date    validTo             = getDate(row, col++);
            Integer area1Id             = getInt(row, col++);
            String  area1En             = getString(row, col++);
            String  area1Da             = getString(row, col++);
            Integer area2Id             = getInt(row, col++);
            String  area2En             = getString(row, col++);
            String  area2Da             = getString(row, col++);
            Integer area3Id             = getInt(row, col++);
            String  area3En             = getString(row, col++);
            String  area3Da             = getString(row, col++);
            String  descriptionEn       = getString(row, col++);
            String  descriptionDa       = getString(row, col++);
            Integer infoType            = getInt(row, col);

            // For the first row, create and initialize the message
            if (message == null) {
                message = new Message();

                message.setId(id);
                message.setCreated(created);
                message.setUpdated(updated);
                message.setVersion(1);
                message.setType(Type.SUBAREA_WARNING);
                message.setStatus(Status.PUBLISHED);

                SeriesIdentifier identifier = new SeriesIdentifier();
                message.setSeriesIdentifier(identifier);
                identifier.setMainType(SeriesIdType.MSI);
                identifier.setAuthority("DK");
                Calendar cal = Calendar.getInstance();
                cal.setTime(validFrom);
                int year = cal.get(Calendar.YEAR);
                identifier.setYear(year);

                message.createDesc("da").setTitle("Skydeøvelser. Advarsel");
                message.createDesc("en").setTitle("Firing Exercises. Warning");
                message.setValidFrom(TimeUtils.resetSeconds(validFrom));
                message.setValidTo(TimeUtils.resetSeconds(validTo));
                formatFiringExerciseTime(message, "da");
                formatFiringExerciseTime(message, "en");

                // Areas
                Area area = createAreaTemplate(area1Id, area1En, area1Da, null);
                area = createAreaTemplate(area2Id, area2En, area2Da, area);
                area = createAreaTemplate(area3Id, area3En, area3Da, area);
                message.setArea(area);

                // Categories
                message.checkCreateCategories().add(getDefaultFiringExerciseCategory());
            }

            // Copy various info types

            // Details
            if (infoType == 1) {
                // Details
                appendDescription(message, "da", null, descriptionDa);
                appendDescription(message, "en", null, descriptionEn);

            } else if (infoType == 2) {
                // Note
                message.getDesc("da").setNote(descriptionDa);
                message.getDesc("en").setNote(descriptionEn);

            } else if (infoType == 3) {
                // Charts
                String charts = descriptionDa.replaceAll("\\.", "");
                for (String chartStr : charts.split(",")) {
                    Matcher m1 = CHART_PATTERN_1.matcher(chartStr.trim());
                    Matcher m2 = CHART_PATTERN_2.matcher(chartStr.trim());
                    if (m1.matches()) {
                        Chart chart = new Chart();
                        chart.setChartNumber(m1.group(1));
                        message.checkCreateCharts().add(chart);
                    } else if (m2.matches()) {
                        Chart chart = new Chart();
                        chart.setChartNumber(m2.group(1));
                        chart.setInternationalNumber(Integer.valueOf(m2.group(2)));
                        message.checkCreateCharts().add(chart);
                    }
                }

            } else if (infoType == 4) {
                // Publication
                message.getDesc("da").setPublication(descriptionDa);
                message.getDesc("en").setPublication(descriptionEn);

            } else if (infoType == 5) {
                // Restriction
                appendDescription(message, "da", "Forbud", descriptionDa);
                appendDescription(message, "en", "Restriction", descriptionEn);

            } else if (infoType == 6) {
                // Signals
                appendDescription(message, "da", "Skydesignaler", descriptionDa);
                appendDescription(message, "en", "Signals", descriptionEn);

            }
        }

        return message;
    }

    /**
     * Reads the location for the legacy firing exercise.
     *
     * @param message the message to read the location for
     * @return the updated message
     */
    private Message readFiringExerciseLocationData(Message message) {
        // Inject the id into the SQL
        String sql = firingExerciseLocationDataSql.replace(":id", message.getId().toString());

        // Execute the DB query
        @SuppressWarnings("unchecked")
        List<Object[]> feData = em.createNativeQuery(sql)
                .getResultList();

        // If there are no points, remove the location
        if (feData.size() == 0) {
            return message;
        }

        // Add the points to the message location
        Location location = new Location();
        message.checkCreateLocations().add(location);
        location.setType(LocationType.POLYGON);

        for (Object[] row : feData) {

            // Read the location point data from the DB
            int col = 0;
            Integer latDeg      = getInt(row, col++);
            Double  latMin      = getDouble(row, col++);
            Integer lonDeg      = getInt(row, col++);
            Double  lonMin      = getDouble(row, col);

            double lat = latDeg.doubleValue() + latMin / 60.0;
            double lon = lonDeg.doubleValue() + lonMin / 60.0;

            // Create the current point
            Point pt = new Point();
            pt.setIndex(location.checkCreatePoints().size() + 1);
            pt.setLat(lat);
            pt.setLon(lon);
            location.checkCreatePoints().add(pt);
        }

        return message;
    }

    /**
     * Formats the time interval for firing exercises
     * @param msg the message
     * @param lang the language
     */
    private void formatFiringExerciseTime(Message msg, String lang) {
        try {
            String format = "da".equals(lang) ? "d MMMM yyyy, 'kl.' HH:mm" : "d MMMM yyyy, 'hours' HH:mm";
            if (TimeUtils.sameDate(msg.getValidFrom(), msg.getValidTo())) {
                SimpleDateFormat sdf1 = new SimpleDateFormat(format, new Locale(lang));
                SimpleDateFormat sdf2 = new SimpleDateFormat("HH:mm");
                msg.getDesc(lang).setTime(String.format("%s - %s", sdf1.format(msg.getValidFrom()), sdf2.format(msg.getValidTo())));
            }
        } catch (Exception e) {
            log.warn("Failed formatting time for message " + msg + ": " + e);
        }
    }

    /**
     * Append the description to the message description field
     * @param msg the message
     * @param lang the language
     * @param subtitle an optional subtitle
     * @param description the description to append
     */
    private void appendDescription(Message msg, String lang, String subtitle, String description) {
        String desc = StringUtils.defaultString(msg.getDesc(lang).getDescription());

        if (StringUtils.isNotBlank(subtitle)) {
            desc += String.format("<p><strong>%s</strong></p>", subtitle);
        }
        if (StringUtils.isNotBlank(description)) {
            desc += String.format("<p>%s</p>", description);
        }

        msg.getDesc(lang).setDescription(desc);
    }

    /**
     * Creates an Area template based on the given Danish and English name
     * and optionally a parent Area
     * @param id the id of the area
     * @param nameEn English name
     * @param nameDa Danish name
     * @param parent parent area
     * @return the Area template, or null if the names are empty
     */
    public static Area createAreaTemplate(Integer id, String nameEn, String nameDa, Area parent) {
        Area area = null;
        if (id != null && (StringUtils.isNotBlank(nameEn) || StringUtils.isNotBlank(nameDa))) {
            area = new Area();
            area.setId(id);
            if (StringUtils.isNotBlank(nameEn)) {
                area.createDesc("en").setName(nameEn);
            }
            if (StringUtils.isNotBlank(nameDa)) {
                area.createDesc("da").setName(nameDa);
            }
            area.setParent(parent);
        }
        return area;
    }

    /**
     * Creates an Category template based on the given Danish and English name
     * and optionally a parent Category
     * @param id the id of the category
     * @param nameEn English name
     * @param nameDa Danish name
     * @param parent parent area
     * @return the Category template, or null if the names are empty
     */
    public static Category createCategoryTemplate(Integer id, String nameEn, String nameDa, Category parent) {
        Category category = null;
        if (id != null && (StringUtils.isNotBlank(nameEn) || StringUtils.isNotBlank(nameDa))) {
            category = new Category();
            category.setId(id);
            if (StringUtils.isNotBlank(nameEn)) {
                category.createDesc("en").setName(nameEn);
            }
            if (StringUtils.isNotBlank(nameDa)) {
                category.createDesc("da").setName(nameDa);
            }
            category.setParent(parent);
        }
        return category;
    }

    private String getString(Object[] row, int index) {
        return (String)row[index];
    }

    private Integer getInt(Object[] row, int index) {
        return (row[index] != null && row[index] instanceof BigInteger) ? (Integer)((BigInteger)row[index]).intValue() : (Integer)row[index];
    }

    private Double getDouble(Object[] row, int index) {
        return (Double)row[index];
    }

    private Date getDate(Object[] row, int index) {
        return (Date)row[index];
    }

    private Boolean getBoolean(Object[] row, int index) {
        return (Boolean)row[index];
    }

}
