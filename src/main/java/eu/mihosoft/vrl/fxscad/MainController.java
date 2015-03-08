/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package eu.mihosoft.vrl.fxscad;

import eu.mihosoft.vrl.v3d.CSG;
import eu.mihosoft.vrl.v3d.MeshContainer;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SnapshotParameters;
import javafx.scene.SubScene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;

import org.apache.commons.io.IOUtils;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.controlsfx.control.action.Action;
import org.controlsfx.dialog.Dialogs;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.StyleSpansBuilder;
import org.reactfx.Change;
import org.reactfx.EventStream;
import org.reactfx.EventStreams;

import com.neuronrobotics.interaction.CadInteractionEvent;
import com.neuronrobotics.sdk.addons.kinematics.AbstractKinematicsNR;
import com.neuronrobotics.sdk.addons.kinematics.DHParameterKinematics;
import com.neuronrobotics.sdk.addons.kinematics.ITaskSpaceUpdateListenerNR;
import com.neuronrobotics.sdk.addons.kinematics.math.TransformNR;
import com.neuronrobotics.sdk.common.Log;
import com.neuronrobotics.sdk.dyio.DyIO;
import com.neuronrobotics.sdk.serial.SerialConnection;
import com.neuronrobotics.sdk.ui.ConnectionDialog;
/**
 * FXML Controller class
 *
 * @author Michael Hoffer &lt;info@michaelhoffer.de&gt;
 */
public class MainController implements Initializable, IFileChangeListener {

    private static final String[] KEYWORDS = new String[]{
        "def", "in", "as", "abstract", "assert", "boolean", "break", "byte",
        "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else",
        "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super",
        "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while"
    };

    private static final Pattern KEYWORD_PATTERN
            = Pattern.compile("\\b(" + String.join("|", KEYWORDS) + ")\\b");

    private final Group viewGroup = new Group();
    private final Group manipulator = new Group();
    private final Group baseGroup = new Group();

    private final CodeArea codeArea = new CodeArea();

    private boolean autoCompile = true;

    private CSG csgObject;

    @FXML
    private TextArea logView;

    @FXML
    private ScrollPane editorContainer;

    @FXML
    private Pane viewContainer;

    private SubScene subScene;

	private File openFile;

	private FileChangeWatcher watcher;
	private int boxSize=50;
	private Box myBox = new Box(boxSize/10,  boxSize/10,boxSize);
	private ArrayList<Sphere> joints = new  ArrayList<Sphere> ();
//	private final Rotate rotateX = new Rotate(0,  Rotate.X_AXIS);
//	private final Rotate rotateZ = new Rotate(0,  Rotate.Z_AXIS);
//	private final Rotate rotateY = new Rotate(0,  Rotate.Y_AXIS);
	
	private final  Affine rotations =  new Affine();
	
	private DHParameterKinematics model;
	
	private OutputStream logWriter = new OutputStream() {
		
		@Override
		public void write(int b) throws IOException {
			String log=new String(new byte[]{(byte) b});
			System.out.println(log);
			logView.setText(logView.getText()+log);
		}
	};
    /**
     * Initializes the controller class.
     *
     * @param url
     * @param rb
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {

        //
        codeArea.textProperty().addListener(
                (ov, oldText, newText) -> {
                    Matcher matcher = KEYWORD_PATTERN.matcher(newText);
                    int lastKwEnd = 0;
                    StyleSpansBuilder<Collection<String>> spansBuilder
                    = new StyleSpansBuilder<>();
                    while (matcher.find()) {
                        spansBuilder.add(Collections.emptyList(),
                                matcher.start() - lastKwEnd);
                        spansBuilder.add(Collections.singleton("keyword"),
                                matcher.end() - matcher.start());
                        lastKwEnd = matcher.end();
                    }
                    spansBuilder.add(Collections.emptyList(),
                            newText.length() - lastKwEnd);
                    codeArea.setStyleSpans(0, spansBuilder.create());
                });

        EventStream<Change<String>> textEvents
                = EventStreams.changesOf(codeArea.textProperty());

        textEvents.reduceSuccessions((a, b) -> b, Duration.ofMillis(500)).
                subscribe(code -> {
                    if (autoCompile) {
                        compile(code.getNewValue());
                    }
                });

        codeArea.replaceText(
                "CSG cube = new Cube(2).toCSG()\n"
                + "CSG sphere = new Sphere(1.25).toCSG()\n"
                + "\n"
                + "cube.difference(sphere)");

        editorContainer.setContent(codeArea);

        subScene = new SubScene(baseGroup, 100, 100, true,
                SceneAntialiasing.BALANCED);

        subScene.widthProperty().bind(viewContainer.widthProperty());
        subScene.heightProperty().bind(viewContainer.heightProperty());

        PerspectiveCamera subSceneCamera = new PerspectiveCamera(false);
        subScene.setCamera(subSceneCamera);
        
        subSceneCamera.layoutXProperty().bind(
                viewContainer.widthProperty().divide(-1));
        subSceneCamera.layoutYProperty().bind(
                viewContainer.heightProperty().divide(-1));
        
//        viewGroup.layoutXProperty().bind(viewContainer.widthProperty().divide(2));
//        viewGroup.layoutYProperty().bind(viewContainer.heightProperty().divide(2));
//        
        manipulator.layoutXProperty().bind(viewContainer.widthProperty().divide(2));
        manipulator.layoutYProperty().bind(viewContainer.heightProperty().divide(1.2));

        myBox.getTransforms().addAll(rotations);
        
        manipulator.getChildren().add(myBox);


        baseGroup.getChildren().add(new Box((300/2)*20,  (300/2)*20,2));
        baseGroup.getChildren().add(viewGroup);
        baseGroup.getChildren().add(manipulator);
        
        
        baseGroup.getTransforms().addAll(
        		//new Rotate(90, Rotate.X_AXIS),
        		new Rotate(180, Rotate.Y_AXIS),
        		new Rotate(180, Rotate.Z_AXIS));
        Platform.runLater(() -> {
        	baseGroup.setTranslateX(-viewContainer.widthProperty().divide(1).doubleValue());
        	viewGroup.setTranslateZ(viewContainer.heightProperty().divide(2).doubleValue());
        	manipulator.setTranslateZ(viewContainer.heightProperty().divide(2).doubleValue());
        	manipulator.getTransforms().add(new Rotate(45, Rotate.Z_AXIS));
        });
        VFX3DUtil.addMouseBehavior(baseGroup,viewContainer);

        viewContainer.getChildren().add(subScene);
        
        DyIO master = new DyIO(new SerialConnection("/dev/DyIO0"));

		master.connect();
		model = new DHParameterKinematics(master,"TrobotMaster.xml");
        Log.enableWarningPrint();
		model.addPoseUpdateListener(new ITaskSpaceUpdateListenerNR() {			
			int packetIndex=0;
			int numSkip = 1;
			int armScale=8;
			@Override
			public void onTaskSpaceUpdate(AbstractKinematicsNR source, TransformNR pose) {
				ArrayList<TransformNR> jointLocations =  model.getChainTransformations();

				if(packetIndex++==numSkip){
					packetIndex=0;
					Platform.runLater(() -> {
				        for(int i=0;i<joints.size();i++){
				        	joints.get(i).setTranslateX(jointLocations.get(i).getX()*armScale);
				        	joints.get(i).setTranslateY(jointLocations.get(i).getY()*armScale);
				        	joints.get(i).setTranslateZ(jointLocations.get(i).getZ()*armScale);
				        	
				        }
						try{
							double [][] poseRot = pose.getRotationMatrixArray();
							rotations.setMxx(poseRot[0][0]);
							rotations.setMxy(poseRot[0][1]);
							rotations.setMxz(poseRot[0][2]);
							rotations.setMyx(poseRot[1][0]);
							rotations.setMyy(poseRot[1][1]);
							rotations.setMyz(poseRot[1][2]);
							rotations.setMzx(poseRot[2][0]);
							rotations.setMzy(poseRot[2][1]);
							rotations.setMzz(poseRot[2][2]);
							rotations.setTx(pose.getX()*armScale);
							rotations.setTy(pose.getY()*armScale);
							rotations.setTz(pose.getZ()*armScale);

						}catch (Exception e){
							e.printStackTrace();
						}
		            });

				}
			}
			
			@Override
			public void onTargetTaskSpaceUpdate(AbstractKinematicsNR source,TransformNR pose) {}
		});
		
        ArrayList<TransformNR> jointLocations =  model.getChainTransformations();
        for(int i=0;i<jointLocations.size();i++){
        	Sphere s = new Sphere(10);
        	s.setMaterial(new PhongMaterial(Color.rgb(0, i*(128/6), 255-i*(128/6))));
        	joints.add(s);
        	manipulator.getChildren().add(s);
        }
        viewGroup.getTransforms().add(rotations);
        System.out.println("Starting Application");
    }

    private void setCode(String code) {
        codeArea.replaceText(code);
    }

    private String getCode() {
        return codeArea.getText();
    }

    private void clearLog() {
        logView.setText("");
    }

    private void compile(String code) {

        csgObject = null;

        //clearLog();

        viewGroup.getChildren().clear();
        StringWriter sw = new StringWriter();
    	PrintWriter pw = new PrintWriter(sw);
        try {

            CompilerConfiguration cc = new CompilerConfiguration();

            cc.addCompilationCustomizers(
                    new ImportCustomizer().
                    addStarImports("eu.mihosoft.vrl.v3d",
                            "eu.mihosoft.vrl.v3d.samples").
                    addStaticStars("eu.mihosoft.vrl.v3d.Transform"));

        	
            Binding binding = new Binding();
            binding.setProperty("out", pw);
            GroovyShell shell = new GroovyShell(getClass().getClassLoader(),
            		binding, cc);

            Script script = shell.parse(code);
 
            Object obj = script.run();
            
            if (obj instanceof CSG) {
            	
                CSG csg = (CSG) obj;

                csgObject = csg;
                CadInteractionEvent interact =new CadInteractionEvent();
                
                MeshContainer meshContainer = csg.toJavaFXMesh(interact);

                final MeshView meshView = meshContainer.getAsMeshViews().get(0);

                setMeshScale(meshContainer,
                        viewContainer.getBoundsInLocal(), meshView);

                PhongMaterial m = new PhongMaterial(Color.RED);

                meshView.setCullFace(CullFace.NONE);

                meshView.setMaterial(m);


//                viewGroup.setTranslateX( viewContainer.widthProperty().divide(2).doubleValue());
//                viewGroup.setTranslateY( viewContainer.heightProperty().divide(2).doubleValue());
                
                viewContainer.boundsInLocalProperty().addListener(
                        (ov, oldV, newV) -> {
                            setMeshScale(meshContainer, newV, meshView);
                        });

                
                
                viewGroup.getChildren().add(meshView);
                logView.setText("Compile OK\n"+logView.getText());

            } else {
                System.out.println(">> no CSG object returned :(");
            }

        } catch (Throwable ex) {
        	ex.printStackTrace(pw);
        
        }
      	logView.setText(sw.toString()+logView.getText());
    }

    private void setMeshScale(
            MeshContainer meshContainer, Bounds t1, final MeshView meshView) {
        double maxDim
                = Math.max(meshContainer.getWidth(),
                        Math.max(meshContainer.getHeight(),
                                meshContainer.getDepth()));

        double minContDim = Math.min(t1.getWidth(), t1.getHeight());

        double scale = minContDim / (maxDim * 2);

        meshView.setScaleX(scale);
        meshView.setScaleY(scale);
        meshView.setScaleZ(scale);
    }

    /**
     * Returns the location of the Jar archive or .class file the specified
     * class has been loaded from. <b>Note:</b> this only works if the class is
     * loaded from a jar archive or a .class file on the locale file system.
     *
     * @param cls class to locate
     * @return the location of the Jar archive the specified class comes from
     */
    public static File getClassLocation(Class<?> cls) {

//        VParamUtil.throwIfNull(cls);
        String className = cls.getName();
        ClassLoader cl = cls.getClassLoader();
        URL url = cl.getResource(className.replace(".", "/") + ".class");

        String urlString = url.toString().replace("jar:", "");

        if (!urlString.startsWith("file:")) {
            throw new IllegalArgumentException("The specified class\""
                    + cls.getName() + "\" has not been loaded from a location"
                    + "on the local filesystem.");
        }

        urlString = urlString.replace("file:", "");
        urlString = urlString.replace("%20", " ");

        int location = urlString.indexOf(".jar!");

        if (location > 0) {
            urlString = urlString.substring(0, location) + ".jar";
        } else {
            //System.err.println("No Jar File found: " + cls.getName());
        }

        return new File(urlString);
    }
    
    @FXML
    private void onLoadFile(ActionEvent e) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open JFXScad File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "JFXScad files (*.jfxscad, *.groovy)",
                        "*.jfxscad", "*.groovy"));
        

        openFile = fileChooser.showOpenDialog(null);

        if (openFile == null) {
            return;
        }

        String fName = openFile.getAbsolutePath();

        if (!fName.toLowerCase().endsWith(".groovy")
                && !fName.toLowerCase().endsWith(".jfxscad")) {
            fName += ".jfxscad";
        }

        try {
            setCode(new String(Files.readAllBytes(Paths.get(fName)), "UTF-8"));
            
            if(watcher!=null){
            	watcher.close();
            }
            watcher = new FileChangeWatcher(openFile);
            watcher.addIFileChangeListener(this);
            watcher.start();
            
        } catch (IOException ex) {
            Logger.getLogger(MainController.class.getName()).
                    log(Level.SEVERE, null, ex);
        }
        
        
        
    }

    @FXML
    private void onSaveFile(ActionEvent e) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save JFXScad File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "JFXScad files (*.jfxscad, *.groovy)",
                        "*.jfxscad", "*.groovy"));
        fileChooser.setInitialDirectory(openFile);
        
        File f = fileChooser.showSaveDialog(null);
        
        if (f == null) {
            return;
        }

        String fName = f.getAbsolutePath();

        if (!fName.toLowerCase().endsWith(".groovy")
                && !fName.toLowerCase().endsWith(".jfxscad")) {
            fName += ".jfxscad";
        }

        try {
            Files.write(Paths.get(fName), getCode().getBytes("UTF-8"));
        } catch (IOException ex) {
            Logger.getLogger(MainController.class.getName()).
                    log(Level.SEVERE, null, ex);
        }
    }

    @FXML
    private void onExportAsSTLFile(ActionEvent e) {

        if (csgObject == null) {
            Action response = Dialogs.create()
                    .title("Error")
                    .message("Cannot export STL. There is no geometry :(")
                    .lightweight()
                    .showError();

            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export STL File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "STL files (*.stl)",
                        "*.stl"));

        File f = fileChooser.showSaveDialog(null);

        if (f == null) {
            return;
        }

        String fName = f.getAbsolutePath();

        if (!fName.toLowerCase().endsWith(".stl")) {
            fName += ".stl";
        }

        try {
            eu.mihosoft.vrl.v3d.FileUtil.write(
                    Paths.get(fName), csgObject.toStlString());
        } catch (IOException ex) {
            Logger.getLogger(MainController.class.getName()).
                    log(Level.SEVERE, null, ex);
        }
    }

    @FXML
    private void onExportAsPngFile(ActionEvent e) {

        if (csgObject == null) {
            Action response = Dialogs.create()
                    .title("Error")
                    .message("Cannot export PNG. There is no geometry :(")
                    .lightweight()
                    .showError();

            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export PNG File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "Image files (*.png)",
                        "*.png"));

        File f = fileChooser.showSaveDialog(null);

        if (f == null) {
            return;
        }

        String fName = f.getAbsolutePath();

        if (!fName.toLowerCase().endsWith(".png")) {
            fName += ".png";
        }

        int snWidth = 1024;
        int snHeight = 1024;

        double realWidth = viewGroup.getBoundsInLocal().getWidth();
        double realHeight = viewGroup.getBoundsInLocal().getHeight();

        double scaleX = snWidth / realWidth;
        double scaleY = snHeight / realHeight;

        double scale = Math.min(scaleX, scaleY);

        PerspectiveCamera snCam = new PerspectiveCamera(false);
        snCam.setTranslateZ(-200);

        SnapshotParameters snapshotParameters = new SnapshotParameters();
        snapshotParameters.setTransform(new Scale(scale, scale));
        snapshotParameters.setCamera(snCam);
        snapshotParameters.setDepthBuffer(true);
        snapshotParameters.setFill(Color.TRANSPARENT);

        WritableImage snapshot = new WritableImage(snWidth, (int) (realHeight * scale));

        viewGroup.snapshot(snapshotParameters, snapshot);

        try {
            ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null),
                    "png", new File(fName));
        } catch (IOException ex) {
            Logger.getLogger(MainController.class.getName()).
                    log(Level.SEVERE, null, ex);
        }
    }

    @FXML
    private void onCompileAndRun(ActionEvent e) {
        compile(getCode());
    }

    @FXML
    private void onServoMountSample(ActionEvent e) {

        try {
            String code = IOUtils.toString(this.getClass().
                    getResourceAsStream("ServoMount.jfxscad"),
                    "UTF-8");
            setCode(code);
        } catch (IOException ex) {
            Logger.getLogger(MainController.class.getName()).
                    log(Level.SEVERE, null, ex);
        }

    }

    @FXML
    private void onBatteryHolderSample(ActionEvent e) {

        try {
            String code = IOUtils.toString(this.getClass().
                    getResourceAsStream("BatteryHolder.jfxscad"),
                    "UTF-8");
            setCode(code);
        } catch (IOException ex) {
            Logger.getLogger(MainController.class.getName()).
                    log(Level.SEVERE, null, ex);
        }

    }

    @FXML
    private void onWheelSample(ActionEvent e) {

        try {
            String code = IOUtils.toString(this.getClass().
                    getResourceAsStream("Wheel.jfxscad"),
                    "UTF-8");
            setCode(code);
        } catch (IOException ex) {
            Logger.getLogger(MainController.class.getName()).
                    log(Level.SEVERE, null, ex);
        }

    }

    @FXML
    private void onBreadBoardConnectorSample(ActionEvent e) {

        try {
            String code = IOUtils.toString(this.getClass().
                    getResourceAsStream("BreadBoardConnector.jfxscad"),
                    "UTF-8");
            setCode(code);
        } catch (IOException ex) {
            Logger.getLogger(MainController.class.getName()).
                    log(Level.SEVERE, null, ex);
        }

    }

    @FXML
    private void onBoardMountSample(ActionEvent e) {

        try {
            String code = IOUtils.toString(this.getClass().
                    getResourceAsStream("BoardMount.jfxscad"),
                    "UTF-8");
            setCode(code);
        } catch (IOException ex) {
            Logger.getLogger(MainController.class.getName()).
                    log(Level.SEVERE, null, ex);
        }

    }

    @FXML
    private void onClose(ActionEvent e) {
        System.exit(0);
    }

    @FXML
    private void onAutoCompile(ActionEvent e) {
        autoCompile = !autoCompile;
    }

    public TextArea getLogView() {
        return logView;
    }

	@Override
	public void onFileChange(File fileThatChanged, WatchEvent event) {
		// TODO Auto-generated method stub
		if(fileThatChanged.getAbsolutePath().contains(openFile.getAbsolutePath())){
			System.out.println("Code in "+fileThatChanged.getAbsolutePath()+" changed");
			Platform.runLater(new Runnable() {
	            @Override
	            public void run() {
	            	try {
						setCode(new String(Files.readAllBytes(Paths.get(fileThatChanged.getAbsolutePath())), "UTF-8"));
					} catch (UnsupportedEncodingException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	            }
	       });

		}else{
			//System.out.println("Othr Code in "+fileThatChanged.getAbsolutePath()+" changed");
		}
	}

}
