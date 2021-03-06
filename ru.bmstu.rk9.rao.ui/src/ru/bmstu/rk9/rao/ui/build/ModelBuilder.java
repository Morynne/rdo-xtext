package ru.bmstu.rk9.rao.ui.build;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISaveableFilter;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.Saveable;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.MarkerUtilities;
import org.eclipse.xtext.builder.EclipseOutputConfigurationProvider;
import org.eclipse.xtext.builder.EclipseResourceFileSystemAccess2;
import org.eclipse.xtext.generator.OutputConfiguration;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.osgi.framework.Bundle;

import ru.bmstu.rk9.rao.IMultipleResourceGenerator;

public class ModelBuilder {
	private static String checkRaoLib(IProject project, IProgressMonitor monitor) {
		String libBundleName = "ru.bmstu.rk9.rao.lib";
		Bundle lib = Platform.getBundle(libBundleName);
		try {
			File libPath = FileLocator.getBundleFile(lib);
			if (libPath == null)
				return "Build failed: cannot locate bundle " + libBundleName;

			IJavaProject jProject = JavaCore.create(project);

			IClasspathEntry[] projectClassPathArray = jProject
					.getRawClasspath();

			IPath libPathBinary;
			if (libPath.isDirectory())
				libPathBinary = new Path(libPath.getAbsolutePath() + "/bin/");
			else
				libPathBinary = new Path(libPath.getAbsolutePath());

			boolean libInClasspath = false;
			for (IClasspathEntry classpathEntry : projectClassPathArray) {
				if (classpathEntry.getPath().equals(libPathBinary)) {
					libInClasspath = true;
					break;
				}
			}

			if (!libInClasspath) {
				List<IClasspathEntry> projectClassPathList = new ArrayList<IClasspathEntry>(
						Arrays.asList(projectClassPathArray));
				IClasspathEntry libEntry = JavaCore.newLibraryEntry(
						libPathBinary, null, null);
				projectClassPathList.add(libEntry);

				jProject.setRawClasspath(
						(IClasspathEntry[]) projectClassPathList
								.toArray(new IClasspathEntry[projectClassPathList
										.size()]), monitor);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return "Build failed: internal error while checking rao lib:\n"
					+ e.getMessage();
		}

		return null;
	}

	private static String checkSrcGen(IProject project, IFolder srcGenFolder,
			IProgressMonitor monitor) {
		IJavaProject jProject = JavaCore.create(project);
		try {
			IClasspathEntry[] projectClassPathArray;
			projectClassPathArray = jProject.getRawClasspath();
			List<IClasspathEntry> projectClassPathList = new ArrayList<IClasspathEntry>(
					Arrays.asList(projectClassPathArray));

			if (srcGenFolder.exists()) {
				for (IResource resource : srcGenFolder.members(true))
					resource.delete(true, new NullProgressMonitor());
			} else {
				srcGenFolder.create(true, true, new NullProgressMonitor());
			}

			boolean srcGenInClasspath = false;
			for (IClasspathEntry classpathEntry : projectClassPathArray) {
				if (classpathEntry.getPath().equals(srcGenFolder.getFullPath())) {
					srcGenInClasspath = true;
					break;
				}
			}

			if (!srcGenInClasspath) {
				IClasspathEntry libEntry = JavaCore.newSourceEntry(
						srcGenFolder.getFullPath(), null, null);
				projectClassPathList.add(libEntry);

				jProject.setRawClasspath(
						(IClasspathEntry[]) projectClassPathList
								.toArray(new IClasspathEntry[projectClassPathList
										.size()]), monitor);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return "Build failed: internal error while checking src-gen:\n"
					+ e.getMessage();
		}

		return null;
	}

	public static Job build(final ExecutionEvent event,
			final EclipseResourceFileSystemAccess2 fsa,
			final IResourceSetProvider resourceSetProvider,
			final EclipseOutputConfigurationProvider ocp,
			final IMultipleResourceGenerator generator) {
		final String pluginId = "ru.bmstu.rk9.rao.ui";
		Job buildJob = new Job("Building Rao model") {
			protected IStatus run(IProgressMonitor monitor) {
				IEditorPart activeEditor = HandlerUtil.getActiveEditor(event);
				if (activeEditor == null)
					return new Status(Status.ERROR, pluginId,
							"Build failed: no editor opened.");

				final IProject project = BuildUtil.getProject(activeEditor);
				if (project == null)
					return new Status(
							Status.ERROR,
							pluginId,
							"Build failed: file '"
									+ activeEditor.getTitle()
									+ "' is not a part of any project in workspace.");

				final Display display = PlatformUI.getWorkbench().getDisplay();
				IWorkbenchWindow workbenchWindow = HandlerUtil
						.getActiveWorkbenchWindow(event);

				ISaveableFilter filter = new ISaveableFilter() {
					@Override
					public boolean select(Saveable saveable,
							IWorkbenchPart[] containingParts) {
						if (!saveable.getName().endsWith(".rao"))
							return false;

						if (containingParts.length < 1)
							return false;

						IWorkbenchPart part = containingParts[0];
						if (!(part instanceof XtextEditor))
							return false;

						XtextEditor editor = (XtextEditor) part;
						if (editor.getResource().getProject().equals(project))
							return true;

						return false;
					}
				};

				display.syncExec(() -> PlatformUI.getWorkbench().saveAll(
						workbenchWindow, workbenchWindow, filter, true));

				String libErrorMessage = checkRaoLib(project, monitor);
				if (libErrorMessage != null)
					return new Status(Status.ERROR, pluginId, libErrorMessage);

				IJobManager jobManager = Job.getJobManager();
				try {
					for (Job projectJob : jobManager.find(project.getName()))
						projectJob.join();
				} catch (Exception e) {
					e.printStackTrace();
				}

				final List<IResource> raoFiles = BuildUtil
						.getAllRaoFilesInProject(project);
				if (raoFiles.isEmpty()) {
					return new Status(Status.ERROR, pluginId,
							"Build failed: project contains no rao files");
				}

				IFolder srcGenFolder = project.getFolder("src-gen");
				String srcGenErrorMessage = checkSrcGen(project, srcGenFolder,
						monitor);
				if (srcGenErrorMessage != null)
					return new Status(Status.ERROR, pluginId,
							srcGenErrorMessage);

				fsa.setOutputPath(srcGenFolder.getFullPath().toString());

				fsa.setMonitor(monitor);
				fsa.setProject(project);

				Map<String, OutputConfiguration> outputConfigurations = new HashMap<String, OutputConfiguration>();

				for (OutputConfiguration oc : ocp
						.getOutputConfigurations(project))
					outputConfigurations.put(oc.getName(), oc);

				fsa.setOutputConfigurations(outputConfigurations);

				final ResourceSet resourceSet = resourceSetProvider
						.get(project);

				boolean projectHasErrors = false;

				for (IResource resource : raoFiles) {
					Resource loadedResource = resourceSet.getResource(
							BuildUtil.getURI(resource), true);
					if (!loadedResource.getErrors().isEmpty()) {
						projectHasErrors = true;
						break;
					}
				}

				if (projectHasErrors) {
					try {
						srcGenFolder.delete(true, new NullProgressMonitor());
					} catch (CoreException e) {
						e.printStackTrace();
					}
					return new Status(Status.ERROR, pluginId,
							"Build failed: model has errors");
				}

				generator.doGenerate(resourceSet, fsa);

				try {
					project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD,
							monitor);
				} catch (CoreException e) {
					e.printStackTrace();
					return new Status(Status.ERROR, pluginId,
							"Build failed: could not build project", e);
				}

				try {
					IMarker[] markers = project.findMarkers(
							IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true,
							IResource.DEPTH_INFINITE);

					if (markers.length > 0) {
						String errorsDetails = "Project contains errors:";
						for (IMarker marker : markers) {
							errorsDetails += "\nfile "
									+ marker.getResource().getName()
									+ " at line "
									+ MarkerUtilities.getLineNumber(marker)
									+ ": " + MarkerUtilities.getMessage(marker);
						}
						return new Status(Status.ERROR, pluginId, errorsDetails);
					}
				} catch (CoreException e) {
					return new Status(
							Status.ERROR,
							pluginId,
							"Build failed: internal error whule calculating error markers",
							e);
				}

				return Status.OK_STATUS;
			}
		};

		buildJob.setPriority(Job.BUILD);
		return buildJob;
	}
}
