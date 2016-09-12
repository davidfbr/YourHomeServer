package net.yourhome.server.net.rest;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.jayway.jsonpath.JsonPath;

import net.yourhome.common.base.enums.PropertyTypes;
import net.yourhome.common.net.model.Configuration;
import net.yourhome.common.net.model.ServerInfo;
import net.yourhome.server.base.SettingsManager;
import net.yourhome.server.base.Util;
import net.yourhome.server.base.ZipFile;
import net.yourhome.server.net.Server;
import net.yourhome.server.net.rest.view.ImageHelper;

@Path("/Project")
public class Project {

	public static final String PROJECT_FOLDER = ImageHelper.HOMEDESIGNER_PATH + "/projects";
	public static final String PUBLISHED_PROJECTS_FOLDER = PROJECT_FOLDER + "/published";
	private static Logger log = Logger.getLogger(Project.class);

	private String imageFolder = SettingsManager.getBasePath() + Server.FILESERVER_PATH + ImageHelper.HOMEDESIGNER_PATH;
	// private ImageHelper imageHelper = ImageHelper.getInstance();

	// POST api/Project/Publish/project123
	@POST
	@Path("/Publish/{projectName}")
	@Produces({ MediaType.APPLICATION_JSON })
	public Response publishProject(@Context final UriInfo uriInfo, @PathParam("projectName") final String projectName, String bodyContent) {
		// Convert project
		String convertedProject = convertJsonToProject(bodyContent, projectName);
		updateServerInfo(new File(convertedProject));
		if (convertedProject == null) {
			return Response.serverError().build();
		}

		return Response.ok().build();
	}

	// DELETE api/Project/Published/project123.zip
	@DELETE
	@Path("/Published/{projectName}")
	public Response unpublish(@Context final UriInfo uriInfo, @PathParam("projectName") final String projectName, String bodyContent) throws IOException, JSONException {
		ServerInfo info = Info.getServerInfo();
		Configuration c = info.getConfigurations().remove(projectName);
		if (c != null) {
			// Also delete file
			String configurationPath = SettingsManager.getBasePath() + Server.FILESERVER_PATH + PUBLISHED_PROJECTS_FOLDER + "/" + c.getFile();
			File configurationFile = new File(configurationPath);
			if (!configurationFile.delete()) {
				return Response.serverError().build();
			}
			;
		}
		Info.writeServerInfo(info);

		return Response.ok().build();
	}

	// POST api/Project/
	@POST
	@Produces({ MediaType.APPLICATION_JSON })
	public String Post(@Context final UriInfo uriInfo, String bodyContent) {
		return saveProject(bodyContent, "");
	}

	// POST api/Project/ProjectName
	@POST
	@Produces({ MediaType.APPLICATION_JSON })
	@Path("/{projectFileName}")
	public String Post(@Context final UriInfo uriInfo, String bodyContent, @PathParam("projectFileName") final String projectName) {
		return saveProject(bodyContent, projectName);
	}

	// GET api/Project
	@GET
	@Produces({ MediaType.APPLICATION_JSON })
	public String Get() {
		File imageFolder = new File(SettingsManager.getBasePath() + Server.FILESERVER_PATH + PROJECT_FOLDER);
		File[] filePaths = imageFolder.listFiles();

		JSONArray projectFiles = new JSONArray();
		if(filePaths != null) {
			for (File filePath : filePaths) {
				String extension = Util.getExtension(filePath);
				if (filePath.isFile() && (extension != null && extension.equals("json"))) {
					projectFiles.put(filePath.getName());
				}
			}
		}

		return projectFiles.toString();

	}

	// GET api/Project/123.zip
	@GET
	@Path("/{projectFileName}")
	@Produces({ MediaType.MULTIPART_FORM_DATA })
	public Response getProject(@Context final UriInfo uriInfo, @PathParam("projectFileName") final String projectFileName, String bodyContent) {
		File projectFile = new File(SettingsManager.getBasePath() + Server.FILESERVER_PATH + PUBLISHED_PROJECTS_FOLDER + '/' + projectFileName);

		if (projectFile.exists()) {
			return Response.ok(projectFile).build();
		} else {
			return Response.serverError().build();
		}
	}

	// DELETE api/Project/project123.zip
	@DELETE
	@Path("/{projectFileName}")
	public Response eleteProject(@Context final UriInfo uriInfo, @PathParam("projectFileName") final String projectFileName, String bodyContent) throws IOException, JSONException {

		String configurationPath = SettingsManager.getBasePath() + Server.FILESERVER_PATH + PROJECT_FOLDER + "/" + projectFileName;
		File configurationFile = new File(configurationPath);
		if (!configurationFile.delete()) {
			return Response.serverError().build();
		}
		;

		return Response.ok().build();
	}

	private String saveProject(String bodyContent, String projectName) {
		// Parse body
		projectName += ".json";
		try {
			// Verify json formatting
			JSONObject projectJsonObject = new JSONObject(bodyContent);

			// Save project as file
			try {
				File projectFolder = new File(SettingsManager.getBasePath() + Server.FILESERVER_PATH + Project.PROJECT_FOLDER);
				if(!projectFolder.exists()) {
					projectFolder.mkdirs();
				}
				File file = new File(projectFolder,  projectName);
				BufferedWriter output = new BufferedWriter(new FileWriter(file));
				output.write(bodyContent);
				output.close();
			} catch (IOException e) {
				log.error("Exception occured: ", e);
			}
		} catch (JSONException e1) {
			e1.printStackTrace();
		}

		// Return
		JSONObject projectInfo = new JSONObject();
		try {
			projectInfo.put("fileName", projectName);
		} catch (JSONException e) {
			log.error("Exception occured: ", e);
		}
		return projectInfo.toString();
	}

	public String convertJsonToProject(String jsonProject, String projectName) {
		//String zipPath = SettingsManager.getBasePath() + NetWebSocketServer.FILESERVER_PATH + PUBLISHED_PROJECTS_FOLDER + "/" + projectName + ".zip";
		File publishPath = new File(SettingsManager.getBasePath() + Server.FILESERVER_PATH + PUBLISHED_PROJECTS_FOLDER);
		if(!publishPath.exists()) {
			publishPath.mkdirs();
		}
		//String zipPath = SettingsManager.getBasePath() + NetWebSocketServer.FILESERVER_PATH + PUBLISHED_PROJECTS_FOLDER + "/" + projectName + ".zip";
		try {
			File zipFile = new File(publishPath, projectName + ".zip");
			//zipFile.mkdirs();
			ZipFile projectZip = new ZipFile(zipFile.getAbsolutePath());

			List<String> imageFieldsList = new ArrayList<String>();
			/* Parse JSON and add all images to the zip */
			readImageProperties(jsonProject, imageFieldsList);

			/* Parse pattern "imageSrc" and add all to the zip */
			JSONObject projectJSONObj = new JSONObject(jsonProject);
			readImagesFromPatternInObject(projectJSONObj, imageFieldsList);

			for (String imagePath : imageFieldsList) {
				try {
					projectZip.addFile(imagePath, imageFolder);
				} catch (IOException e) {
					log.error("[Configuration] Could not add image: " + imagePath + " (" + e.getMessage() + ")");
				}
			}

			// log.debug("[Configuration] Images: "+imageFieldsList.toString());

			projectZip.addTextFile("configuration.json", jsonProject);
			projectZip.close();

			return zipFile.getAbsolutePath();

		} catch (FileNotFoundException e) {
			log.error("Exception occured: ", e);
		} catch (JSONException e) {
			log.error("Exception occured: ", e);
		} catch (IOException e) {
			log.error("Exception occured: ", e);
		}
		return null;
	}

	private void readImageProperties(String currentObject, List<String> intoList) {
		// Navigate into project pages and get all properties with type image,
		// image_state.
		List<List<Map>> properties = JsonPath.read(currentObject, "$.pages[*].objects[*].viewProperties.properties");
		for (List<Map> propertyList : properties) {
			for (Map property : propertyList) {
				PropertyTypes ptype = PropertyTypes.convert((String) property.get("type"));
				if (ptype == PropertyTypes.IMAGE || ptype == PropertyTypes.IMAGE_STATE) {
					intoList.add((String) property.get("value"));
				}
			}
		}
	}

	private void readImagesFromPatternInObject(JSONObject currentObject, List<String> intoList) {
		String[] namesCurrentLevel = JSONObject.getNames(currentObject);
		for (String name : namesCurrentLevel) {
			try {
				Object childObject = currentObject.get(name);
				if (childObject instanceof JSONArray) {
					readImagesFromPatternInArray((JSONArray) childObject, intoList);
				} else if (childObject instanceof JSONObject) {
					readImagesFromPatternInObject((JSONObject) childObject, intoList);
				} else {
					// Not an object: check if this field contains an image path
					if (childObject instanceof String && name.toLowerCase().endsWith("imagesrc")) {
						String imageSrc = childObject.toString();
						if (imageSrc != null && !childObject.equals("")) {
							intoList.add((String) childObject);
						}
					}
				}
			} catch (JSONException e) {
				log.error("Exception occured: ", e);
			}
		}
	}

	private void readImagesFromPatternInArray(JSONArray array, List<String> intoList) {
		for (int i = 0; i < array.length(); i++) {
			Object childObject;
			try {
				childObject = array.get(i);
				if (childObject instanceof JSONArray) {
					readImagesFromPatternInArray((JSONArray) childObject, intoList);
				} else if (childObject instanceof JSONObject) {
					readImagesFromPatternInObject((JSONObject) childObject, intoList);
				}
			} catch (JSONException e) {
				log.error("Exception occured: ", e);
			}
		}
	}

	private void updateServerInfo(File newConfigFile) {
		try {
			ServerInfo serverInfo = Info.getServerInfo();

			// See if this configuration is already published before
			net.yourhome.common.net.model.Configuration currentConfiguration = serverInfo.getConfigurations().get(newConfigFile.getName());
			if (currentConfiguration != null) {
				// Update this one
				currentConfiguration.setVersion(currentConfiguration.getVersion() + 1);
				currentConfiguration.setSize(newConfigFile.length());
			} else {
				// Create new one
				Configuration c = new Configuration();
				c.setFile(newConfigFile.getName());
				c.setName(newConfigFile.getName().replace(".zip", ""));
				c.setVersion(1);
				c.setSize(newConfigFile.length());
				serverInfo.getConfigurations().put(c.getFile(), c);
			}

			Info.writeServerInfo(serverInfo);

		} catch (IOException e) {
			log.error("Exception occured: ", e);
		} catch (JSONException e) {
			log.error("Exception occured: ", e);
		}

		// XML Initialization
		/*
		 * DocumentBuilderFactory docFactory =
		 * DocumentBuilderFactory.newInstance(); DocumentBuilder docBuilder;
		 * File serverInfoFile = new File(Settings.getBasePath(),
		 * NetWebSocketServer.FILESERVER_PATH + "/serverinfo.xml"); int
		 * versionNumber = 0; try { docBuilder =
		 * docFactory.newDocumentBuilder(); Document serverInfoXMLDocument =
		 * docBuilder.parse(serverInfoFile);
		 * 
		 * NodeList portNodes =
		 * serverInfoXMLDocument.getElementsByTagName("port"); if
		 * (portNodes.getLength() > 0 && portNodes.item(0).getNodeType() ==
		 * Node.ELEMENT_NODE) { Element portElement = (Element)
		 * portNodes.item(0);
		 * portElement.getFirstChild().setNodeValue(Settings.getStringValue(
		 * Settings.NET_HTTP_PORT)); }
		 * 
		 * NodeList configurationNodes =
		 * serverInfoXMLDocument.getElementsByTagName("configuration"); if
		 * (configurationNodes.getLength() > 0 &&
		 * configurationNodes.item(0).getNodeType() == Node.ELEMENT_NODE) {
		 * Element configurationElement = (Element) configurationNodes.item(0);
		 * NodeList versionNodes =
		 * configurationElement.getElementsByTagName("version"); if
		 * (versionNodes.getLength() > 0 && versionNodes.item(0).getNodeType()
		 * == Node.ELEMENT_NODE) { Element versionElement = (Element)
		 * versionNodes.item(0); versionNumber =
		 * Integer.parseInt(versionElement.getFirstChild().getNodeValue());
		 * 
		 * // New version number: versionNumber++;
		 * versionElement.getFirstChild().setNodeValue(versionNumber + ""); }
		 * NodeList fileNodes =
		 * configurationElement.getElementsByTagName("file"); if
		 * (fileNodes.getLength() > 0 && fileNodes.item(0).getNodeType() ==
		 * Node.ELEMENT_NODE) { Element fileElement = (Element)
		 * fileNodes.item(0);
		 * fileElement.getFirstChild().setNodeValue("configurations/" +
		 * newConfigFile.getName()); } }
		 * 
		 * // Convert document xml to string and write it TransformerFactory
		 * transFactory = TransformerFactory.newInstance(); Transformer
		 * transformer = transFactory.newTransformer(); StringWriter buffer =
		 * new StringWriter();
		 * transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,
		 * "yes"); transformer.transform(new DOMSource(serverInfoXMLDocument),
		 * new StreamResult(buffer)); String versionXMLString =
		 * buffer.toString(); BufferedWriter output = new BufferedWriter(new
		 * FileWriter(serverInfoFile)); output.write(versionXMLString);
		 * output.close(); } catch (ParserConfigurationException e) { log.error(
		 * "Exception occured: ", e); } catch (SAXException e) { log.error(
		 * "Exception occured: ", e); } catch (IOException e) { log.error(
		 * "Exception occured: ", e); } catch (TransformerConfigurationException
		 * e) { log.error("Exception occured: ", e); } catch
		 * (TransformerException e) { log.error("Exception occured: ", e); }
		 */
	}

}