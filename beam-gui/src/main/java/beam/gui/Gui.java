package beam.gui;

import beam.utils.BeamConfigUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.utils.io.IOUtils;

import javax.swing.*;
import javax.swing.GroupLayout.Alignment;
import javax.swing.LayoutStyle.ComponentPlacement;
import java.awt.*;
import java.io.File;
import java.io.IOException;

/**
 * @author mrieser / Senozon AG
 */
public class Gui extends JFrame {

    private static final long serialVersionUID = 1L;

    private JTextField txtConfigFilename;
    private JTextField txtRam;
    private JTextField txtJvmLocation;
    private JTextField txtOutput;

    private JButton btnStartMatsim;
    private JProgressBar progressBar;
    private JTextArea textStdOut;
    private JButton btnEdit;

    private ExeRunner exeRunner = null;

    private PopulationSampler popSampler = null;
    private JTextArea textErrOut;

    private File configFile;
    private File lastUsedDirectory;
    private ConfigEditor editor = null;

    private Gui(final String title) {
        setTitle(title);

        this.lastUsedDirectory = new File(".");

        JLabel lblConfigurationFile = new JLabel("Configuration file:");

        txtConfigFilename = new JTextField();
        txtConfigFilename.setText("");
        txtConfigFilename.setColumns(10);

        btnStartMatsim = new JButton("Run BEAM");
        btnStartMatsim.setEnabled(false);

        JLabel lblWorkDirectory = new JLabel("Filepaths must either be absolute or relative to the location of the config file.");

        JButton btnChoose = new JButton("Choose");
        btnChoose.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setCurrentDirectory(Gui.this.lastUsedDirectory);
            int result = chooser.showOpenDialog(null);
            if (result == JFileChooser.APPROVE_OPTION) {
                File f = chooser.getSelectedFile();
                Gui.this.lastUsedDirectory = f.getParentFile();
                loadConfigFile(f);
                if (Gui.this.editor != null) {
                    Gui.this.editor.closeEditor();
                    Gui.this.editor = null;
                }
            }
        });

        this.btnEdit = new JButton("Edit…");
        this.btnEdit.setEnabled(false);
        this.btnEdit.addActionListener(e -> {
            if (Gui.this.editor == null) {
                this.editor = new ConfigEditor(Gui.this.configFile, Gui.this::loadConfigFile);
            }
            Gui.this.editor.showEditor();
            Gui.this.editor.toFront();
        });

//		JLabel lblYouAreRunning = new JLabel("You are using MATSim version:");

        JTextField txtMatsimVersion = new JTextField();
        txtMatsimVersion.setEditable(false);
        txtMatsimVersion.setText(Gbl.getBuildInfoString());
        txtMatsimVersion.setColumns(10);

        JLabel lblOutputDirectory = new JLabel("Output Directory:");

        JLabel lblMemory = new JLabel("Memory:");

        txtRam = new JTextField();
        txtRam.setText("1024");
        txtRam.setColumns(10);

        JLabel lblMb = new JLabel("MB");

        JLabel lblYouAreUsing = new JLabel("You are using Java version:");

        String javaVersion = "";
        if (!System.getProperty("java.version").startsWith("1.8")) {
            javaVersion += "ERROR - INCOMPATIBLE VERSION, USE JVM 1.8, your version: ";
        }
        javaVersion += System.getProperty("java.version") + "; "
                + System.getProperty("java.vm.vendor") + "; "
                + System.getProperty("java.vm.info") + "; "
                + System.getProperty("sun.arch.data.model") + "-bit";

        JTextField txtJvmversion = new JTextField();
        txtJvmversion.setEditable(false);
        txtJvmversion.setText(javaVersion);
        txtJvmversion.setColumns(10);

        JLabel lblJavaLocation = new JLabel("Java Location:");

        String jvmLocation;
        if (System.getProperty("os.name").startsWith("Win")) {
            jvmLocation = System.getProperties().getProperty("java.home") + File.separator + "bin" + File.separator + "java.exe";
        } else {
            jvmLocation = System.getProperties().getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        }

        txtJvmLocation = new JTextField();
        txtJvmLocation.setEditable(false);
        txtJvmLocation.setText(jvmLocation);
        txtJvmLocation.setColumns(10);

        txtOutput = new JTextField();
        txtOutput.setEditable(false);
        txtOutput.setText("");
        txtOutput.setColumns(10);

        progressBar = new JProgressBar();
        progressBar.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);

        btnStartMatsim.addActionListener(e -> {
            if (exeRunner == null) {
                startMATSim();
            } else {
                stopMATSim();
            }
        });

        JButton btnOpen = new JButton("Open");
        btnOpen.addActionListener(e -> {
            if (!Gui.this.txtOutput.getText().isEmpty()) {
                try {
                    File f = new File(Gui.this.txtOutput.getText());
                    Desktop.getDesktop().open(f);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        });

        JButton btnDelete = new JButton("Delete");
        btnDelete.addActionListener(e -> {
            if (!Gui.this.txtOutput.getText().isEmpty()) {
                int i = JOptionPane.showOptionDialog(Gui.this, "Do you really want to delete the output directory? This action cannot be undone.", "Delete Output Directory", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, new String[]{"Cancel", "Delete"}, "Cancel");
                if (i == 1) {
                    try {
                        IOUtils.deleteDirectoryRecursively(new File(Gui.this.txtOutput.getText()).toPath());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });

        JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
//		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        GroupLayout groupLayout = new GroupLayout(getContentPane());
        groupLayout.setHorizontalGroup(
                groupLayout.createParallelGroup(Alignment.TRAILING)
                        .addGroup(Alignment.LEADING, groupLayout.createSequentialGroup()
                                .addContainerGap()
                                .addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
                                        .addComponent(tabbedPane, GroupLayout.DEFAULT_SIZE, 729, Short.MAX_VALUE)
                                        .addComponent(lblWorkDirectory)
                                        .addComponent(lblJavaLocation)
                                        .addComponent(lblConfigurationFile)
                                        .addComponent(lblOutputDirectory)
                                        .addGroup(groupLayout.createSequentialGroup()
                                                .addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
                                                        .addComponent(lblYouAreUsing)
                                                        .addComponent(lblMemory)
                                                        .addComponent(btnStartMatsim))
                                                .addPreferredGap(ComponentPlacement.RELATED)
                                                .addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
                                                        .addGroup(groupLayout.createSequentialGroup()
                                                                .addComponent(txtRam, GroupLayout.PREFERRED_SIZE, 69, GroupLayout.PREFERRED_SIZE)
                                                                .addPreferredGap(ComponentPlacement.RELATED)
                                                                .addComponent(lblMb))
                                                        .addComponent(txtJvmversion, GroupLayout.DEFAULT_SIZE, 285, Short.MAX_VALUE)
                                                        .addComponent(txtJvmLocation, GroupLayout.DEFAULT_SIZE, 285, Short.MAX_VALUE)
                                                        .addGroup(groupLayout.createSequentialGroup()
                                                                .addComponent(txtConfigFilename, GroupLayout.DEFAULT_SIZE, 188, Short.MAX_VALUE)
                                                                .addPreferredGap(ComponentPlacement.RELATED)
                                                                .addComponent(btnChoose)
                                                                .addPreferredGap(ComponentPlacement.RELATED)
                                                                .addComponent(btnEdit)
                                                        )
                                                        .addComponent(progressBar, GroupLayout.DEFAULT_SIZE, 285, Short.MAX_VALUE)
                                                        .addGroup(groupLayout.createSequentialGroup()
                                                                .addComponent(txtOutput, GroupLayout.DEFAULT_SIZE, 112, Short.MAX_VALUE)
                                                                .addPreferredGap(ComponentPlacement.RELATED)
                                                                .addComponent(btnOpen)
                                                                .addPreferredGap(ComponentPlacement.RELATED)
                                                                .addComponent(btnDelete)))))
                                .addContainerGap())
        );
        groupLayout.setVerticalGroup(
                groupLayout.createParallelGroup(Alignment.LEADING)
                        .addGroup(groupLayout.createSequentialGroup()
                                .addContainerGap()
                                .addPreferredGap(ComponentPlacement.RELATED)
                                .addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
                                        .addComponent(lblYouAreUsing)
                                        .addComponent(txtJvmversion, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(ComponentPlacement.RELATED)
                                .addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
                                        .addComponent(lblJavaLocation)
                                        .addComponent(txtJvmLocation, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(ComponentPlacement.RELATED)
                                .addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
                                        .addComponent(lblConfigurationFile)
                                        .addComponent(txtConfigFilename, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                        .addComponent(btnChoose)
                                        .addComponent(btnEdit))
                                .addPreferredGap(ComponentPlacement.RELATED)
                                .addComponent(lblWorkDirectory)
                                .addPreferredGap(ComponentPlacement.RELATED)
                                .addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
                                        .addComponent(lblOutputDirectory)
                                        .addComponent(txtOutput, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                        .addComponent(btnDelete)
                                        .addComponent(btnOpen))
                                .addPreferredGap(ComponentPlacement.RELATED)
                                .addGroup(groupLayout.createParallelGroup(Alignment.BASELINE)
                                        .addComponent(lblMemory)
                                        .addComponent(txtRam, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                                        .addComponent(lblMb))
                                .addPreferredGap(ComponentPlacement.UNRELATED)
                                .addGroup(groupLayout.createParallelGroup(Alignment.LEADING)
                                        .addComponent(btnStartMatsim)
                                        .addComponent(progressBar, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(ComponentPlacement.RELATED)
                                .addComponent(tabbedPane, GroupLayout.DEFAULT_SIZE, 180, Short.MAX_VALUE)
                                .addContainerGap())
        );

        textStdOut = new JTextArea();
        textStdOut.setWrapStyleWord(true);
        textStdOut.setTabSize(4);
        textStdOut.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textStdOut);
        tabbedPane.addTab("Output", null, scrollPane, null);

        JScrollPane scrollPane_1 = new JScrollPane();
        tabbedPane.addTab("Warnings & Errors", null, scrollPane_1, null);

        textErrOut = new JTextArea();
        textErrOut.setWrapStyleWord(true);
        textErrOut.setTabSize(4);
        textErrOut.setEditable(false);
        scrollPane_1.setViewportView(textErrOut);

        getContentPane().setLayout(groupLayout);

        JMenuBar menuBar = new JMenuBar();
        setJMenuBar(menuBar);

        JMenu mnTools = new JMenu("Tools");
        menuBar.add(mnTools);

        JMenuItem mntmCompressFile = new JMenuItem("Compress File…");
        mnTools.add(mntmCompressFile);
        mntmCompressFile.addActionListener(e -> GUnZipper.gzipFile());

        JMenuItem mntmUncompressFile = new JMenuItem("Uncompress File…");
        mnTools.add(mntmUncompressFile);
        mntmUncompressFile.addActionListener(e -> GUnZipper.gunzipFile());

        mnTools.addSeparator();

        JMenuItem mntmCreateDefaultConfig = new JMenuItem("Create Default config.xml…");
        mnTools.add(mntmCreateDefaultConfig);
        mntmCreateDefaultConfig.addActionListener(e -> {
            SaveFileSaver chooser = new SaveFileSaver();
            chooser.setSelectedFile(new File("defaultConfig.xml"));
            int saveResult = chooser.showSaveDialog(null);
            if (saveResult == JFileChooser.APPROVE_OPTION) {
                File destFile = chooser.getSelectedFile();
                Config config = ConfigUtils.createConfig();
                new ConfigWriter(config).write(destFile.getAbsolutePath());
            }
        });

        JMenuItem mntmCreateSamplePopulation = new JMenuItem("Create Sample Population…");
        mnTools.add(mntmCreateSamplePopulation);
        mntmCreateSamplePopulation.addActionListener(e -> {
            if (popSampler == null) {
                popSampler = new PopulationSampler();
                popSampler.pack();
            }
            popSampler.setVisible(true);
        });
    }

    private void startMATSim() {
        progressBar.setVisible(true);
        progressBar.setEnabled(true);
        this.btnStartMatsim.setEnabled(false);

        new Thread(() -> {
            String classpath = System.getProperty("java.class.path");
            String[] cpParts = classpath.split(File.pathSeparator);
            StringBuilder absoluteClasspath = new StringBuilder();
            for (String cpPart : cpParts) {
                if (absoluteClasspath.length() > 0) {
                    absoluteClasspath.append(File.pathSeparatorChar);
                }
                absoluteClasspath.append(new File(cpPart).getAbsolutePath());
            }
            String[] cmdArgs = new String[]{
                    txtJvmLocation.getText(),
                    "-cp",
                    absoluteClasspath.toString(),
                    "-Xmx" + txtRam.getText() + "m",
                    "beam.sim.RunBeam",
                    "--config",
                    txtConfigFilename.getText()
            };
            Gui.this.textStdOut.setText("");
            Gui.this.textErrOut.setText("");
            Gui.this.exeRunner = ExeRunner.run(cmdArgs, Gui.this.textStdOut, Gui.this.textErrOut, new File(txtConfigFilename.getText()).getParent());
            Gui.this.btnStartMatsim.setText("Stop BEAM");
            Gui.this.btnStartMatsim.setEnabled(true);
            int exitcode = exeRunner.waitForFinish();
            Gui.this.exeRunner = null;

            SwingUtilities.invokeLater(() -> {
                progressBar.setVisible(false);
                btnStartMatsim.setText("Run BEAM");
                btnStartMatsim.setEnabled(true);
            });

            if (exitcode != 0) {
                Gui.this.textStdOut.append("\n");
                Gui.this.textStdOut.append("The simulation did not run properly. Error/Exit code: " + exitcode);
                Gui.this.textStdOut.setCaretPosition(Gui.this.textStdOut.getDocument().getLength());
                Gui.this.textErrOut.append("\n");
                Gui.this.textErrOut.append("The simulation did not run properly. Error/Exit code: " + exitcode);
                Gui.this.textErrOut.setCaretPosition(Gui.this.textStdOut.getDocument().getLength());
                throw new RuntimeException("There was a problem running BEAM. exit code: " + exitcode);
            }
        }).start();

    }

    private void loadConfigFile(final File configFile) {
        this.configFile = configFile;
        String configFilename = configFile.getAbsolutePath();

        com.typesafe.config.Config config;
        try {
            config = BeamConfigUtils.parseFileSubstitutingInputDirectory(this.configFile).resolve();
        } catch (Exception e) {
            Gui.this.textStdOut.setText("");
            Gui.this.textStdOut.append("The configuration file could not be loaded. Error message:\n");
            Gui.this.textStdOut.append(e.getMessage());
            Gui.this.textErrOut.setText("");
            Gui.this.textErrOut.append("The configuration file could not be loaded. Error message:\n");
            Gui.this.textErrOut.append(e.getMessage());
            return;
        }
        txtConfigFilename.setText(configFilename);

        File outputDir = new File(config.getString("beam.outputs.baseOutputDirectory"));
        try {
            txtOutput.setText(configFile.toPath().getParent().resolve(outputDir.toPath()).toFile().getCanonicalPath());
        } catch (IOException e1) {
            txtOutput.setText(configFile.toPath().getParent().resolve(outputDir.toPath()).toFile().getAbsolutePath());
        }

        btnStartMatsim.setEnabled(true);
        btnEdit.setEnabled(true);
    }

    private void stopMATSim() {
        ExeRunner runner = this.exeRunner;
        if (runner != null) {
            runner.killProcess();
            SwingUtilities.invokeLater(() -> {
                progressBar.setVisible(false);
                btnStartMatsim.setText("Run BEAM");
                btnStartMatsim.setEnabled(true);

                Gui.this.textStdOut.append("\n");
                Gui.this.textStdOut.append("The simulation was stopped forcefully.");
                Gui.this.textStdOut.setCaretPosition(Gui.this.textStdOut.getDocument().getLength());
                Gui.this.textErrOut.append("\n");
                Gui.this.textErrOut.append("The simulation was stopped forcefully.");
                Gui.this.textErrOut.setCaretPosition(Gui.this.textStdOut.getDocument().getLength());
            });
        }
    }

    private static Gui showGui() {
        System.setProperty("apple.laf.useScreenMenuBar", "true");

        Gui gui = new Gui("BEAM");
        gui.pack();
        gui.setLocationByPlatform(true);
        gui.setVisible(true);
        return gui;
    }

    public static void main(String[] args) {
        Gui gui = Gui.showGui();
        if (args.length > 0) {
            File configFile = new File(args[0]);
            if (configFile.exists()) {
                gui.loadConfigFile(configFile);
            }
        }
    }

}
