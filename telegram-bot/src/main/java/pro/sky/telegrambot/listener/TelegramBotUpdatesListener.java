package pro.sky.telegrambot.listener;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SendMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import pro.sky.telegrambot.model.NotificationTask;
import pro.sky.telegrambot.repository.NotificationTaskRepository;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelegramBotUpdatesListener implements UpdatesListener {

    private final Logger logger = LoggerFactory.getLogger(TelegramBotUpdatesListener.class);

    private final Pattern pattern = Pattern
            .compile("(\\d{2}\\.\\d{2}\\.\\d{4}\\s\\d{2}:\\d{2})\\s([a-zA-zа-яА-я\\s\\,\\.[0-9]]+)*");

    private final String patternExample = "Example: 04.11.2023 08:00 Water the flowers";

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private NotificationTaskRepository taskRepository;

    private TelegramBot telegramBot;

    public TelegramBotUpdatesListener(TelegramBot telegramBot,
                                      NotificationTaskRepository taskRepository) {
        this.telegramBot = telegramBot;
        this.taskRepository = taskRepository;
    }

    @PostConstruct
    public void init() {
        telegramBot.setUpdatesListener(this);
    }

    @Override
    public int process(List<Update> updates) {
        updates.forEach(update -> {
            logger.info("Processing update: {}", update);
            if (checkForNonStringInput(update.message().text(), update.message().chat().id())) {
                return;
            }
            if (!greetUserOnStart(update)) {
                saveMessage(update);
            }
        });
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    /**
     * Method that greets user on "/start"
     * @param update
     * @return true if user sent "/start" and false if not
     */
    private boolean greetUserOnStart(Update update) {
        long chatId = update.message().chat().id();
        String text = update.message().text();
        if (text.equals("/start")) {
            telegramBot.execute(new SendMessage(chatId, "Hi there! It's notification bot. " +
                    "I'll notify you when it's time to do a task.\n" +
                    "Type info about your task: date, time, text.\n" + patternExample));
            return true;
        }
        return false;
    }

    /**
     * Saves message from user to DB
     *
     * @param update
     * @return true if message matches the pattern and if it was successfully saved.
     * False if message doesn't match or if date is incorrect and hasn't been saved.
     */
    private boolean saveMessage(Update update) {
        long chatId = update.message().chat().id();
        String text = update.message().text();
        Matcher matcher = pattern.matcher(text);
        if (matcher.matches()) {
            try {
                LocalDateTime time = LocalDateTime
                        .parse(matcher.group(1), formatter);
                taskRepository.save(new NotificationTask(
                        chatId,
                        matcher.group(2),
                        time
                ));
                telegramBot.execute(new SendMessage(chatId, "Task has been saved. " +
                        "Rest assured you will get a notification message in the right time."));
                return true;
            } catch (DateTimeParseException e) {
                telegramBot.execute(new SendMessage(chatId,
                        "Please look at the date and time, you have entered the incorrect value."));
            }
        } else {
            telegramBot.execute(
                    new SendMessage(chatId, "Something went wrong.\n" + patternExample)
            );
        }
        return false;
    }

    /**
     * Sends notification and then deletes it from DB
     */
    @Scheduled(cron = "0 0/1 * * * *")
    public void checkTasks() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        ArrayList<NotificationTask> tasks = new ArrayList<>(taskRepository.findByNotifyTime(now));

        for (NotificationTask task : tasks) {
            telegramBot.execute(new SendMessage(task.getChatId(), task.getMessage()));
            taskRepository.delete(task);
        }
    }

    private boolean checkForNonStringInput(String input, long chatId) {
        if (input == null) {
            telegramBot.execute(new SendMessage(chatId, "I can only save text messages"));
            return true;
        }
        return false;
    }
}
