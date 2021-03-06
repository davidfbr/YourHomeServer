/*-
 * Copyright (c) 2016 Coteq, Johan Cosemans
 * All rights reserved.
 *
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY COTEQ AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.yourhome.server.base;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;
import net.yourhome.common.base.enums.ControllerTypes;
import net.yourhome.common.base.enums.MessageLevels;
import net.yourhome.common.base.enums.ValueTypes;
import net.yourhome.common.net.messagestructures.JSONMessage;
import net.yourhome.common.net.messagestructures.general.*;
import net.yourhome.common.net.model.Device;
import net.yourhome.common.net.model.binding.ControlIdentifiers;
import net.yourhome.server.AbstractController;
import net.yourhome.server.ControllerNode;
import net.yourhome.server.ControllerValue;
import net.yourhome.server.IController;
import net.yourhome.server.base.rules.scenes.Scene;
import net.yourhome.server.base.rules.scenes.SceneManager;
import net.yourhome.server.base.rules.scenes.actions.notifications.PushNotificationService;
import net.yourhome.server.net.Server;
import net.yourhome.server.radio.BasicPlayer;
import org.apache.log4j.Logger;
import org.json.JSONException;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;

public class GeneralController extends AbstractController {

	public enum Settings {

		SERVER_NAME(
				new Setting("SERVER_NAME", "Server name")
		), NET_HTTP_PORT(
				new Setting("NET_HTTP_PORT", "Server Port")
		), NET_USERNAME(
				new Setting("NET_USERNAME", "Username of UI Designer", "Leave empty if none")
		), NET_PASSWORD(
				new Setting("NET_PASSWORD", "Password of UI Designer", "Leave empty if none")
		), PROTECTED_PASSCODE(
				new Setting("PROTECTED_PASSCODE", "" + "Passcode for protected buttons (numeric)", "1234")
		), SMTP_ADDRESS(
				new Setting("SMTP_ADDRESS", "SMTP Address (get one free at eg app.mailjet.com/signup)", "in-v3.mailjet.com")
		), SMTP_PORT(
				new Setting("SMTP_PORT", "SMTP Port")
		), SMTP_USER(
				new Setting("SMTP_USER", "SMTP User")
		), SMTP_PASSWORD(
				new Setting("SMTP_PASSWORD", "SMTP Password")
		), SMTP_SENDER(
				new Setting("SMTP_SENDER", "SMTP Sender Email")
		), SMS_KEY(
				new Setting("SMS_KEY", "SMS API Key (get one at nexmo.com)", "c164e41d")
		), SMS_PASSWORD(
				new Setting("SMS_PASSWORD", "SMS API Secret key", "9448240b")
		), SUNSET_LAT(
				new Setting("SUNSET_LAT", "Server Latitude (see www.latlong.net)", "50.8503")
		), SUNSET_LONG(
				new Setting("SUNSET_LONG", "Server Longitude", "4.3517")
		);
		private Setting setting;

		private Settings(Setting setting) {
			this.setting = setting;
		}

		public Setting get() {
			return this.setting;
		}
	}

	private DatabaseConnector dbConnector = DatabaseConnector.getInstance();

	// Singleton instance
	private static Logger log = Logger.getLogger("net.yourhome.server.base.General");
	private static volatile GeneralController generalControllerInstance;
	private static Object lock = new Object();

	public static GeneralController getInstance() {
		GeneralController r = GeneralController.generalControllerInstance;
		if (r == null) {
			synchronized (GeneralController.lock) { // while we were waiting for
													// the lock, another
				r = GeneralController.generalControllerInstance; // thread may
																	// have
																	// instantiated
				// the object
				if (r == null) {
					r = new GeneralController();
					GeneralController.generalControllerInstance = r;
				}
			}
		}
		return GeneralController.generalControllerInstance;
	}

	@Override
	public JSONMessage parseNetMessage(JSONMessage message) {
		if (message instanceof GCMRegistrationMessage) {
			return processGCMRegistrationMessage((GCMRegistrationMessage) message);
		} else if (message instanceof ValueHistoryRequest) {
			return processHistoryValuesRequest((ValueHistoryRequest)message);
		} else if (message instanceof ActivationMessage
                || message instanceof VoiceActivationMessage) {
			return processActivationRequest((ActivationMessage)message);
		} else if (message instanceof SetValueMessage) {
			return processSetValueRequest((SetValueMessage)message);
		} else if (message.controlIdentifiers.getNodeIdentifier().equals("Navigation")) {
			ClientMessageMessage succesMessage = new ClientMessageMessage("Correct PIN Entered", MessageLevels.INFORMATION);
			return succesMessage;
		}
		return null;
	}

	@Override
	public void init() {
		super.init();
		try {
			this.enableSunsetSunriseEvents();
		} catch (Exception e) {
			log.error("Error on scheduling sunrise/sunset events", e);
		}
		Server.getInstance().init();

		log.info("Initialized");
	}

	@Override
	public String getIdentifier() {
		return ControllerTypes.GENERAL.convert();
	}

	@Override
	public List<JSONMessage> initClient() {
		return null;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public String getName() {
		return "General";
	}

	@Override
	public List<ControllerNode> getNodes() {
		/*
		 * Nodes: * General - Message - Scene activation - Wait - Execute native
		 * system command
		 */
		List<ControllerNode> returnList = new ArrayList<ControllerNode>();
		ControllerNode commandsNode = new ControllerNode(this, "Commands", "Commands", "");
		commandsNode.addValue(new ControllerValue(ValueTypes.SEND_NOTIFICATION.convert(), "Send Notification", ValueTypes.SEND_NOTIFICATION));
		commandsNode.addValue(new ControllerValue(ValueTypes.SOUND_NOTIFICATION.convert(), "Play Notification", ValueTypes.SOUND_NOTIFICATION));
		commandsNode.addValue(new ControllerValue(ValueTypes.WAIT.convert(), "Wait", ValueTypes.WAIT));
		commandsNode.addValue(new ControllerValue(ValueTypes.SYSTEM_COMMAND.convert(), "System Command", ValueTypes.SYSTEM_COMMAND));
		returnList.add(commandsNode);

		/* Scenes */
		ControllerNode scenesNode = new ControllerNode(this, "Scenes", "Scenes", "scenes");
		List<Scene> allScenes;
		try {
			allScenes = SceneManager.getAllScenes();
			for (Scene scene : allScenes) {
				scenesNode.addValue(new ControllerValue(scene.getId() + "", scene.getName(), ValueTypes.SCENE_ACTIVATION));
			}
		} catch (SQLException e) {
			log.error("Exception occured: ", e);
		}
		returnList.add(scenesNode);

		/* Page navigation */
		ControllerNode navigationNode = new ControllerNode(this, "Navigation", "Navigation", "navigation_node");
		returnList.add(navigationNode);

		return returnList;
	}

	@Override
	public String getValueName(ControlIdentifiers valueIdentifier) {
		return null;
	}

	@Override
	public List<ControllerNode> getTriggers() {
		/*
		 * Nodes: * General - Time > Periodic - Time > Sunrise - Time > Sunset -
		 * Music > Music Started - Music > Music Stopped
		 */
		List<ControllerNode> returnList = new ArrayList<ControllerNode>();

		ControllerNode timeNode = new ControllerNode(this, "Time", "Time", "");
		timeNode.addValue(new ControllerValue("Periodic", "Periodic", ValueTypes.TIME_PERIOD));
		timeNode.addValue(new ControllerValue("Sunrise", "Sunrise", ValueTypes.EVENT));
		timeNode.addValue(new ControllerValue("Sunset", "Sunset", ValueTypes.EVENT));

		ControllerNode musicNode = new ControllerNode(this, "Music", "Music", "");
		musicNode.addValue(new ControllerValue("MusicStarted", "Music Started", ValueTypes.EVENT));
		musicNode.addValue(new ControllerValue("MusicStopped", "Music Stopped", ValueTypes.EVENT));

		ControllerNode scenesNode = new ControllerNode(this, "Scenes", "Activation of Scene", "scenes");
		List<Scene> allScenes;
		try {
			allScenes = SceneManager.getAllScenes();
			for (Scene scene : allScenes) {
				scenesNode.addValue(new ControllerValue(scene.getId() + "", scene.getName(), ValueTypes.SCENE_ACTIVATION));
			}
		} catch (SQLException e) {
			log.error("Exception occured: ", e);
		}

		returnList.add(timeNode);
		returnList.add(musicNode);
		returnList.add(scenesNode);
		return returnList;
	}

	private TimerTask sunsetTask;

	private void enableSunsetSunriseEvents() throws Exception {
		// Schedule every day the sunrise/sunset events. The sunset event will
		// schedule the events for the next day
		// Location location = new Location("50.8503", "4.3517"); // Brussels
		Location location = new Location(SettingsManager.getStringValue(this.getIdentifier(), Settings.SUNSET_LAT.get()), SettingsManager.getStringValue(this.getIdentifier(), Settings.SUNSET_LONG.get()));
		SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(location, TimeZone.getDefault());
		Calendar now = Calendar.getInstance();
		Calendar sunrise = calculator.getOfficialSunriseCalendarForDate(now);
		if (!sunrise.before(now)) {
			log.debug("Scheduling sunrise at " + new SimpleDateFormat("HH:mm:ss").format(sunrise.getTime()));
			this.sunsetTask = Scheduler.getInstance().schedule(new TimerTask() {
				@Override
				public void run() {
					GeneralController.this.triggerSunrise();
				}
			}, sunrise.getTime(), 0);
		} else {
			Calendar sunset = calculator.getOfficialSunsetCalendarForDate(now);
			if (!sunset.before(now)) {
				log.debug("Scheduling sunset at " + new SimpleDateFormat("HH:mm:ss").format(sunset.getTime()));
				this.sunsetTask = Scheduler.getInstance().schedule(new TimerTask() {
					@Override
					public void run() {
						GeneralController.this.triggerSunset();
					}
				}, sunset.getTime(), 0);
			} else {
				// Schedule the sunrise of tomorrow
				now.add(Calendar.DAY_OF_MONTH, 1);
				Calendar sunriseOfTomorrow = calculator.getOfficialSunriseCalendarForDate(now);
				log.debug("Scheduling sunrise tomorrow at " + new SimpleDateFormat("HH:mm:ss").format(sunriseOfTomorrow.getTime()));
				this.sunsetTask = Scheduler.getInstance().schedule(new TimerTask() {
					@Override
					public void run() {
						GeneralController.this.triggerSunrise();
					}
				}, sunriseOfTomorrow.getTime(), 0);
			}
		}
	}

	private void triggerSunset() {
		log.debug("Sunset! Good night!");
		this.triggerEvent("Time", "Sunset");
		// Schedule sunrise event
		Location location = new Location("50.8503", "4.3517"); // Brussels
		SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(location, TimeZone.getDefault());
		Calendar tomorrow = Calendar.getInstance();
		tomorrow.add(Calendar.DAY_OF_WEEK, 1);
		Calendar sunrise = calculator.getOfficialSunriseCalendarForDate(tomorrow);
		try {
			log.debug("Scheduling sunrise tomorrow at " + new SimpleDateFormat("HH:mm:ss").format(sunrise.getTime()));
			Scheduler.getInstance().schedule(new TimerTask() {
				@Override
				public void run() {
					GeneralController.this.triggerSunrise();
				}
			}, sunrise.getTime(), 0);
		} catch (Exception e) {
			log.error("Exception occured: ", e);
		}
	}

	private void triggerSunrise() {
		log.debug("Sunrise! Good morning!");
		this.triggerEvent("Time", "Sunrise");

		// Schedule sunset event
		Location location = new Location("50.8503", "4.3517"); // Brussels
		SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(location, TimeZone.getDefault());
		Calendar sunset = calculator.getOfficialSunsetCalendarForDate(Calendar.getInstance());
		try {
			log.debug("Scheduling sunset " + new SimpleDateFormat("HH:mm:ss").format(sunset.getTime()));
			Scheduler.getInstance().schedule(new TimerTask() {
				@Override
				public void run() {
					GeneralController.this.triggerSunset();
				}
			}, sunset.getTime(), 0);
		} catch (Exception e) {
			log.error("Exception occured: ", e);
		}
	}

	public void triggerMusicStopped() {
		this.triggerEvent("Music", "MusicStopped");
	}

	public void triggerMusicStarted() {
		this.triggerEvent("Music", "MusicStarted");
	}

	public void triggerSceneActivated(Scene scene) {
		this.triggerEvent("Scenes", scene.getId() + "");
	}

	@Override
	public String getValue(ControlIdentifiers valueIdentifiers) {
		return null;
	}

	@Override
	public boolean isInitialized() {
		return true;
	}

	@Override
	public List<Setting> getSettings() {
		List<Setting> returnList = new ArrayList<Setting>();
		for (Settings s : Settings.values()) {
			returnList.add(s.setting);
		}
		return returnList;
	}

	@Override
	public void destroy() {
		super.destroy();
		if (this.sunsetTask != null) {
			this.sunsetTask.cancel();
		}
		GeneralController.generalControllerInstance = null;
	}


    private JSONMessage processGCMRegistrationMessage(GCMRegistrationMessage message) {
        // Google cloud registration
        GCMRegistrationMessage GCMMessage = (GCMRegistrationMessage) message;
        PushNotificationService GCMService = PushNotificationService.getInstance();
        try {
            GCMService.registerClient(new Device(GCMMessage.registrationId, GCMMessage.name, GCMMessage.screenWidth, GCMMessage.screenHeight));
        } catch (SQLException e) {
            log.error("Exception occured: ", e);
        }
        return null;
    }

    private JSONMessage processHistoryValuesRequest(ValueHistoryRequest message2) {
        // Prepare answer message
        ValueHistoryMessage historyMessage = new ValueHistoryMessage();
        historyMessage.controlIdentifiers = message2.controlIdentifiers;
        historyMessage.offset = message2.offset;

        try {
            String operation = "value as value";
            switch (message2.operation) {
                case AVERAGE:
                    operation = "avg(value_d) as value_d";
                    break;
                case DELTA:
                    operation = "max(value_d)-min(value_d) as value_d";
                    break;
                case MAX:
                    operation = "max(value_d) as value_d";
                    break;
                case MIN:
                    operation = "min(value_d) as value_d";
                    break;
            }
            String select = null;
            ResultSet dataTable = null;
            switch (message2.periodType) {
                case REALTIME:
                    // Select data from database (no operation possible)
                    select = "SELECT time as datetime,strftime('%s', time) as time,value_d, unit " + "  FROM home_history" + " WHERE value_identifier='" + message2.controlIdentifiers.getValueIdentifier() + "' " + "   AND " + " node_identifier = '" + message2.controlIdentifiers.getNodeIdentifier() + "' " + "   AND " + " controller_identifier = '" + message2.controlIdentifiers.getControllerIdentifier().convert() + "' " + "GROUP BY datetime " + "ORDER BY datetime DESC LIMIT " + message2.offset + "," + message2.historyAmount;
                    dataTable = this.dbConnector.executeSelect(select, true);
                    break;
                case DAILY:
                    select = "SELECT strftime('%s',date(day)) as time,unit," + operation + " from ( SELECT time,strftime('%Y-%m-%d', time) as day,value_d, unit" + " 		FROM home_history" + " WHERE value_identifier='" + message2.controlIdentifiers.getValueIdentifier() + "' " + "   AND " + " node_identifier = '" + message2.controlIdentifiers.getNodeIdentifier() + "' " + "   AND " + "controller_identifier = '" + message2.controlIdentifiers.getControllerIdentifier().convert() + "' " + " 		GROUP BY time) as deltaPerDay" + " GROUP BY day" + " ORDER BY time DESC LIMIT " + message2.offset + "," + message2.historyAmount;

                    if (message2.historyAmount > 31 && message2.offset == 0) {
                        dataTable = this.dbConnector.executeSelectArchiving(select, true);
                    } else {
                        dataTable = this.dbConnector.executeSelect(select, true);
                    }
                    break;
                case WEEKLY:
                    select = "SELECT strftime('%s',date(time,'weekday 0','-6 days')) as time,unit," + operation + " from ( SELECT time,strftime('%Y-%W', time) as week,value_d, unit" + " 		FROM home_history" + " WHERE value_identifier='" + message2.controlIdentifiers.getValueIdentifier() + "' " + "   AND " + " node_identifier = '" + message2.controlIdentifiers.getNodeIdentifier() + "' " + "   AND " + "controller_identifier = '" + message2.controlIdentifiers.getControllerIdentifier().convert() + "' " + " 		GROUP BY time) as deltaPerWeek" + " GROUP BY week" + " ORDER BY time DESC LIMIT " + message2.offset + "," + message2.historyAmount;

                    if (message2.historyAmount > 4 && message2.offset == 0) {
                        dataTable = this.dbConnector.executeSelectArchiving(select, true);
                    } else {
                        dataTable = this.dbConnector.executeSelect(select, true);
                    }
					/*
					 * SELECT strftime('%s',date(time,'weekday 0','-6 days')) as
					 * time,max(value)-min(value) as value from ( SELECT time,
					 * strftime('%Y-%W', time) as week, value FROM home_history
					 * WHERE nodeid='39' AND valueid = '659324930' AND homeId =
					 * '3239454784' AND instance = '1' GROUP BY time) as
					 * deltaPerWeek group by week
					 */
                    break;
                case MONTHLY:
					/*
					 * SELECT strftime('%s',date(month)) as
					 * time,max(value)-min(value) as value from ( SELECT
					 * strftime('%Y-%m-01', time) as month,value FROM
					 * home_history WHERE nodeid='39' AND valueid = '659324930'
					 * AND homeId = '3239454784' AND instance = '1' GROUP BY
					 * time) as deltaPerMonth group by month
					 */
                    select = "SELECT strftime('%s',date(month)) as time,unit," + operation + " from ( SELECT time,strftime('%Y-%m-01', time) as month,value_d,unit" + " 		FROM home_history" + " WHERE value_identifier='" + message2.controlIdentifiers.getValueIdentifier() + "' " + "   AND " + " node_identifier = '" + message2.controlIdentifiers.getNodeIdentifier() + "' " + "   AND " + "controller_identifier = '" + message2.controlIdentifiers.getControllerIdentifier().convert() + "' " + " 		GROUP BY time) as deltaPerMonth" + " GROUP BY month" + " ORDER BY time DESC LIMIT " + message2.offset + "," + message2.historyAmount;
                    dataTable = this.dbConnector.executeSelectArchiving(select, true);

                    break;
            }
            if (dataTable != null) {
                while (dataTable.next()) {
                    historyMessage.sensorValues.time.add(dataTable.getInt("time"));
                    if (historyMessage.sensorValues.valueUnit == null || historyMessage.sensorValues.valueUnit == "") {
                        historyMessage.sensorValues.valueUnit = dataTable.getString("unit");
                    }
                    Double value = dataTable.getDouble("value_d");
                    if (value != null) {
                        historyMessage.sensorValues.value.add(value);
                    }
                }
                dataTable.close();
            }

            // Get name of value and use as graph title
            IController sourceController = Server.getInstance().getControllers().get(message2.controlIdentifiers.getControllerIdentifier().convert());
            String title = "";
            if (sourceController != null) {
                title = sourceController.getValueName(message2.controlIdentifiers);
                if (title == null) {
                    title = "";
                }
            }
            historyMessage.title = title;
        } catch (SQLException e) {
            log.error("Exception occured: ", e);
        }
        return historyMessage;
    }

    private JSONMessage processActivationRequest(ActivationMessage message) {
        // Scenes
        if (message.controlIdentifiers.getNodeIdentifier().equals("Scenes")) {
            ClientMessageMessage informClientsMessage = new ClientMessageMessage();
            try {
                Scene sceneToActivate = null;

                if(message instanceof VoiceActivationMessage) {
                    // voice scene activation
                    VoiceActivationMessage voiceMessage = (VoiceActivationMessage)message;
                    List<Scene> matchingScenes = SceneManager.getScenesByName(voiceMessage.voiceParameters.get("sceneName"));
                    if(matchingScenes.size()==1) {
                        sceneToActivate = matchingScenes.get(0);
                    }else {
                        informClientsMessage.broadcast = false;
						informClientsMessage.messageLevel = MessageLevels.ERROR;
                        informClientsMessage.messageContent = matchingScenes.size()+" scenes found with the name "+voiceMessage.voiceParameters.get("sceneName");
                        return informClientsMessage;
                    }
                }else {
                    // normal scene activation
                    sceneToActivate = SceneManager.getScene(Integer.parseInt(message.controlIdentifiers.getValueIdentifier()));
                }

                 if (sceneToActivate != null && sceneToActivate.activate()) {
                    informClientsMessage.broadcast = true;
                    informClientsMessage.messageContent = "Scene " + sceneToActivate.getName() + " activated";
                    return informClientsMessage;
                } else {
                    informClientsMessage.broadcast = false;
                    if(sceneToActivate == null) {
                        informClientsMessage.messageContent = "Scene "+ message.controlIdentifiers.getValueIdentifier()+ " does not exist";
                    }else {
                        informClientsMessage.messageContent = "Failed to activate scene " + sceneToActivate.getName();
                    }
                    return informClientsMessage;
                }
            } catch (NumberFormatException | SQLException | JSONException e) {
                log.error("Exception occured: ", e);
                informClientsMessage.broadcast = false;
                informClientsMessage.messageContent = "Could not activate scene";
                return informClientsMessage;
            }
        } else if (message.controlIdentifiers.getNodeIdentifier().equals("Commands")) {
            if (message.controlIdentifiers.getValueIdentifier().equals(ValueTypes.SOUND_NOTIFICATION.convert())) {
                BasicPlayer notificationPlayer = new BasicPlayer();
                try {
                    notificationPlayer.setDataSource(new File(SettingsManager.getBasePath(), "sounds/doorbell-1.mp3"));
                    notificationPlayer.setVolume(100);
                    notificationPlayer.startPlayback();
                } catch (UnsupportedAudioFileException | LineUnavailableException | IOException e) {
                    log.error("Exception occured: ", e);
                }
            }
        }
        return null;
    }

    private JSONMessage processSetValueRequest(SetValueMessage setValueMessage) {
        if (setValueMessage.controlIdentifiers.getNodeIdentifier().equals("Commands")) {

            if (setValueMessage.controlIdentifiers.getValueIdentifier().equals(ValueTypes.SYSTEM_COMMAND.convert())) {
                Runtime r = Runtime.getRuntime();
                Process p;
                BufferedReader b = null;
                try {
                    String[] command = new String[] { "bash", "-c", setValueMessage.value };
                    p = r.exec(command);

                    p.waitFor();
                    b = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line = "";
                    while ((line = b.readLine()) != null) {
						log.info("Console: " + line);
                    }
                    b.close();
                } catch (IOException e) {
                    log.error("Error on performing action", e);
                } catch (InterruptedException e) {
                    log.error("Error on performing action", e);
                } finally {
                    if (b != null) {
                        try {
                            b.close();
                        } catch (IOException e) {
                        }
                    }
                }
            } else if (setValueMessage.controlIdentifiers.getValueIdentifier().equals(ValueTypes.WAIT.convert())) {
                int seconds = Integer.parseInt(setValueMessage.value);
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException e) {
                    log.error("Exception occured: ", e);
                }
            }
        }
        return null;
    }
}
