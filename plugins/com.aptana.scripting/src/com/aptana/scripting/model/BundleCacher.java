package com.aptana.scripting.model;

import java.beans.IntrospectionException;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SubMonitor;
import org.jruby.Ruby;
import org.jruby.RubyProc;
import org.jruby.RubyRegexp;
import org.jruby.util.KCode;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

import com.aptana.scope.ScopeSelector;
import com.aptana.scripting.ScriptingActivator;
import com.aptana.scripting.ScriptingEngine;

/**
 * This class serializes and deserializes the scripting model for a given bundle.
 * 
 * @author cwilliams
 */
public class BundleCacher
{

	/**
	 * The file where we store our serialized model.
	 */
	private static final String CACHE_FILE = "cache.yml"; //$NON-NLS-1$

	private static final String REGEXP_TAG = "!regexp"; //$NON-NLS-1$
	private static final String SCOPE_SELECTOR_TAG = "!scope"; //$NON-NLS-1$
	private static final String ENVIRONMENT_TAG = "!environment"; //$NON-NLS-1$
	private static final String COMMAND_TAG = "!command"; //$NON-NLS-1$

	private LoadCycleListener listener;

	private class BundleCacheInvalidatingLoadCycleListener implements LoadCycleListener
	{
		public void scriptUnloaded(File script)
		{
			// if file has been deleted, update the cache!
			if (!script.exists())
			{
				File bundleDir = getBundleDir(script);
				// Update the cache
				cache(bundleDir, new NullProgressMonitor());
			}
		}

		public void scriptReloaded(File script)
		{
			// if file is newer than cache, update the cache!
			updateCacheIfNecessary(script);
		}

		public void scriptLoaded(File script)
		{
			updateCacheIfNecessary(script);
		}

		private void updateCacheIfNecessary(File script)
		{
			File bundleDir = getBundleDir(script);
			File cacheFile = new File(bundleDir, CACHE_FILE);

			List<File> bundleFiles = new ArrayList<File>();
			bundleFiles.add(script);
			if (anyFilesNewer(cacheFile, bundleFiles, new NullProgressMonitor()))
			{
				// Update the cache
				cache(bundleDir, new NullProgressMonitor());
			}
		}
	}

	public BundleCacher()
	{
		listener = new BundleCacheInvalidatingLoadCycleListener();
		BundleManager.getInstance().addLoadCycleListener(listener);
	}

	protected File getBundleDir(File script)
	{
		return BundleManager.getInstance().getBundleDirectory(script);
	}

	public void dispose()
	{
		if (listener != null)
		{
			BundleManager.getInstance().removeLoadCycleListener(listener);
		}
		listener = null;
	}

	public void cache(File bundleDirectory, IProgressMonitor monitor)
	{
		// Force bundle manager to load the bundle...
		if (!Platform.isRunning())
		{
			BundleManager.getInstance().loadBundle(bundleDirectory);
		}
		// grab the bundle model
		BundleElement be = BundleManager.getInstance().getBundleFromPath(bundleDirectory);
		cache(be);
	}

	protected boolean cache(BundleElement be)
	{
		if (be == null)
		{
			return false;
		}

		// Now write the config file out...
		FileWriter writer = null;
		try
		{
			File configFile = new File(be.getBundleDirectory(), CACHE_FILE);
			writer = new FileWriter(configFile);

			Yaml yaml = createYAML(be.getBundleDirectory());
			yaml.dump(be, writer);
			return true;
		}
		catch (IOException e)
		{
			ScriptingActivator.logError(e.getMessage(), e);
		}
		finally
		{
			if (writer != null)
			{
				try
				{
					writer.close();
				}
				catch (IOException e)
				{
					// ignore
				}
			}
		}
		return false;
	}

	public BundleElement load(final File bundleDirectory, List<File> bundleFiles, IProgressMonitor monitor)
	{
		SubMonitor sub = SubMonitor.convert(monitor, 100);
		BundleElement be = null;
		try
		{
			File cacheFile = new File(bundleDirectory, CACHE_FILE);
			if (!cacheFile.exists())
			{
				return null;
			}

			// IF any file is newer, ignore the cache, it'll get rewritten
			if (anyFilesNewer(cacheFile, bundleFiles, sub.newChild(10)))
			{
				return null;
			}

			// Load up the bundle contents from the cache
			FileReader reader = null;
			try
			{
				Yaml yaml = createYAML(bundleDirectory);
				reader = new FileReader(cacheFile);
				sub.subTask(MessageFormat.format(Messages.BundleCacher_LoadCacheTaskName,
						bundleDirectory.getAbsolutePath()));
				be = (BundleElement) yaml.load(reader);
				sub.worked(80);

				// If any file has been deleted, ignore the cache, it'll get rewritten
				if (anyFileDeleted(be, sub.newChild(10)))
				{
					return null;
				}
			}
			catch (Exception e)
			{
				ScriptingActivator.logError(e.getMessage(), e);
				ScriptingActivator.logInfo(MessageFormat.format(Messages.BundleCacher_LoadingYAMLError,
						bundleDirectory.getAbsolutePath()));
			}
			finally
			{
				if (reader != null)
				{
					try
					{
						reader.close();
					}
					catch (IOException e)
					{
						// ignore
					}
				}
			}
		}
		finally
		{
			sub.done();
		}
		return be;
	}

	private boolean anyFilesNewer(File cacheFile, List<File> bundleFiles, IProgressMonitor monitor)
	{
		SubMonitor sub = SubMonitor.convert(monitor, bundleFiles.size());
		try
		{
			// Compare lastMod versus all the bundleFiles.
			long lastMod = cacheFile.lastModified();
			for (File file : bundleFiles)
			{
				sub.subTask(MessageFormat.format(Messages.BundleCacher_ComparingTimestampSubTaskName,
						file.getAbsolutePath()));
				// TODO Just update the cache with the updated files/diff!
				if (file.lastModified() > lastMod)
				{
					// One of the files is newer, don't load cache! This will reload everything from disk and rewrite
					// the
					// cache
					ScriptingActivator.logInfo(MessageFormat.format(Messages.BundleCacher_OutOfDateCacheMsg,
							file.getPath()));
					return true;
				}
			}
		}
		finally
		{
			sub.done();
		}
		return false;
	}

	private boolean anyFileDeleted(BundleElement be, IProgressMonitor monitor)
	{
		if (be == null)
		{
			return false;
		}
		// FIXME If a file is deleted, remove all the elements contributed from that path and rewrite the cache!
		// (i.e. just update the diff!)
		List<AbstractBundleElement> children = be.getChildren();
		if (children == null)
		{
			return false;
		}
		SubMonitor sub = SubMonitor.convert(monitor, children.size());
		try
		{
			for (AbstractBundleElement abe : be.getChildren())
			{
				String path = abe.getPath();
				if (!new File(path).exists())
				{
					ScriptingActivator.logInfo(MessageFormat.format(
							Messages.BundleCacher_FileReferencedInCacheMissingMsg, path, abe.toString()));
					return true;
				}
				if (abe instanceof MenuElement)
				{
					MenuElement menu = (MenuElement) abe;
					return anyFileDeleted(menu);
				}
				sub.worked(1);
			}
		}
		finally
		{
			sub.done();
		}
		return false;
	}

	private boolean anyFileDeleted(MenuElement parent)
	{
		if (parent == null || !parent.hasChildren())
		{
			return false;
		}
		for (MenuElement child : parent.getChildren())
		{
			String path = child.getPath();
			if (!new File(path).exists())
			{
				ScriptingActivator.logInfo(MessageFormat.format(Messages.BundleCacher_FileReferencedInCacheMissingMsg,
						path, child.toString()));
				return true;
			}
			return anyFileDeleted(child);
		}
		return false;
	}

	private Yaml createYAML(File bundleDirectory)
	{
		return new Yaml(new RubyRegexpConstructor(bundleDirectory), new MyRepresenter(bundleDirectory));
	}

	private class MyRepresenter extends Representer
	{
		private File bundleDirectory;

		public MyRepresenter(File bundleDirectory)
		{
			this.bundleDirectory = bundleDirectory;
			this.representers.put(RubyRegexp.class, new RepresentRubyRegexp());
			this.representers.put(ScopeSelector.class, new RepresentScopeSelector());
			this.addClassTag(LazyCommandElement.class, new Tag(COMMAND_TAG));
			this.addClassTag(LazyEnvironmentElement.class, new Tag(ENVIRONMENT_TAG));
		}

		@Override
		protected NodeTuple representJavaBeanProperty(Object javaBean, Property property, Object propertyValue,
				Tag customTag)
		{
			if (javaBean instanceof AbstractElement)
			{
				if ("path".equals(property.getName())) //$NON-NLS-1$
				{
					String path = (String) propertyValue;
					IPath relative = Path.fromOSString(path).makeRelativeTo(
							Path.fromOSString(bundleDirectory.getAbsolutePath()));
					propertyValue = relative.toOSString();
				}
			}
			return super.representJavaBeanProperty(javaBean, property, propertyValue, customTag);
		}

		@Override
		protected Set<Property> getProperties(Class<? extends Object> type) throws IntrospectionException
		{
			if (type.equals(Ruby.class) || type.equals(KCode.class) || type.equals(RubyProc.class))
			{
				return Collections.emptySet();
			}
			Set<Property> set = super.getProperties(type);
			if (CommandElement.class.isAssignableFrom(type) || type.equals(EnvironmentElement.class))
			{
				// drop runtime, invoke, and invoke block properties
				Set<Property> toRemove = new HashSet<Property>();
				for (Property prop : set)
				{
					if ("invokeBlock".equals(prop.getName()) || "runtime".equals(prop.getName()) //$NON-NLS-1$ //$NON-NLS-2$
							|| "invoke".equals(prop.getName())) //$NON-NLS-1$
					{
						toRemove.add(prop);
					}
				}

				set.removeAll(toRemove);
			}
			return set;
		}

		/**
		 * Store ruby regexps as strings.
		 * 
		 * @author cwilliams
		 */
		private class RepresentRubyRegexp implements Represent
		{
			public Node representData(Object data)
			{
				RubyRegexp dice = (RubyRegexp) data;
				String value = dice.toString();
				return representScalar(new Tag(REGEXP_TAG), value);
			}
		}

		/**
		 * Store scope selectors as strings.
		 * 
		 * @author cwilliams
		 */
		private class RepresentScopeSelector implements Represent
		{
			public Node representData(Object data)
			{
				ScopeSelector dice = (ScopeSelector) data;
				String value = dice.toString();
				return representScalar(new Tag(SCOPE_SELECTOR_TAG), value);
			}
		}
	}

	class RubyRegexpConstructor extends Constructor
	{

		private File bundleDirectory;

		public RubyRegexpConstructor(File bundleDirectory)
		{
			this.bundleDirectory = bundleDirectory;
			this.yamlConstructors.put(new Tag(SCOPE_SELECTOR_TAG), new ConstructScopeSelector());
			this.yamlConstructors.put(new Tag(REGEXP_TAG), new ConstructRubyRegexp());
			this.yamlConstructors.put(new Tag(COMMAND_TAG), new ConstructCommandElement());
			this.yamlConstructors.put(new Tag(ENVIRONMENT_TAG), new ConstructEnvironmentElement());
			this.yamlConstructors.put(new Tag(BundleElement.class), new ConstructBundleElement());
			this.yamlConstructors.put(new Tag(MenuElement.class), new ConstructMenuElement());
			this.yamlConstructors.put(new Tag(SnippetElement.class), new ConstructSnippetElement());
			this.yamlConstructors.put(new Tag(ContentAssistElement.class), new ConstructContentAssistElement());
			this.yamlConstructors.put(new Tag(CommandElement.class), new ConstructCommandElement());
			this.yamlConstructors.put(new Tag(TemplateElement.class), new ConstructTemplateElement());
			this.yamlConstructors.put(new Tag(SmartTypingPairsElement.class), new ConstructSmartTypingPairsElement());
			this.yamlConstructors.put(new Tag(ProjectTemplateElement.class), new ConstructProjectTemplateElement());
			this.yamlConstructors.put(new Tag(EnvironmentElement.class), new ConstructEnvironmentElement());

			// Tell it that "children" field for MenuElement is a list of MenuElements
			TypeDescription menuDescription = new TypeDescription(MenuElement.class);
			menuDescription.putListPropertyType("children", MenuElement.class); //$NON-NLS-1$
			addTypeDescription(menuDescription);
		}

		private class ConstructScopeSelector extends AbstractConstruct
		{
			public Object construct(Node node)
			{
				String val = (String) constructScalar((ScalarNode) node);
				return new ScopeSelector(val);
			}
		}

		private class ConstructRubyRegexp extends AbstractConstruct
		{
			public Object construct(Node node)
			{
				String val = (String) constructScalar((ScalarNode) node);
				return RubyRegexp.newRegexp(ScriptingEngine.getInstance().getScriptingContainer().getProvider()
						.getRuntime(), val, 0);
			}
		}

		// TODO All these subclasses are pretty much the same. Pass in a Class type to constructor and use reflection to
		// reduce duplication!
		private abstract class AbstractBundleElementConstruct extends AbstractConstruct
		{
			/**
			 * Grab the path from the mapping node and grab it's value!
			 * 
			 * @param node
			 * @return
			 */
			protected String getPath(Node node)
			{
				String path = null;
				if (node instanceof MappingNode)
				{
					MappingNode map = (MappingNode) node;
					List<NodeTuple> nodes = map.getValue();
					for (NodeTuple tuple : nodes)
					{
						Node keyNode = tuple.getKeyNode();
						if (keyNode instanceof ScalarNode)
						{
							ScalarNode scalar = (ScalarNode) keyNode;
							String valueOfKey = scalar.getValue();
							if ("path".equals(valueOfKey)) //$NON-NLS-1$
							{
								Node valueNode = tuple.getValueNode();
								if (valueNode instanceof ScalarNode)
								{
									ScalarNode scalarValue = (ScalarNode) valueNode;
									path = scalarValue.getValue();
									break;
								}
							}
						}
					}
				}
				if (path != null)
				{
					IPath pathObj = Path.fromOSString(path);
					if (!pathObj.isAbsolute())
					{
						// Prepend the bundle directory.
						path = bundleDirectory.getAbsolutePath() + File.separator + path;
					}
				}
				return path;
			}
		}

		private class ConstructBundleElement extends AbstractBundleElementConstruct
		{
			public Object construct(Node node)
			{
				node.setType(BundleElement.class);
				String path = getPath(node);
				BundleElement be = new BundleElement(path);
				Construct mappingConstruct = yamlClassConstructors.get(NodeId.mapping);
				mappingConstruct.construct2ndStep(node, be);
				be.setPath(path);
				return be;
			}
		}

		private class ConstructCommandElement extends AbstractBundleElementConstruct
		{
			public Object construct(Node node)
			{
				node.setType(CommandElement.class);
				String path = getPath(node);
				CommandElement be = new LazyCommandElement(path);
				Construct mappingConstruct = yamlClassConstructors.get(NodeId.mapping);
				mappingConstruct.construct2ndStep(node, be);
				be.setPath(path);
				return be;
			}
		}

		private class ConstructSnippetElement extends AbstractBundleElementConstruct
		{
			public Object construct(Node node)
			{
				node.setType(SnippetElement.class);
				String path = getPath(node);
				SnippetElement be = new SnippetElement(path);
				Construct mappingConstruct = yamlClassConstructors.get(NodeId.mapping);
				mappingConstruct.construct2ndStep(node, be);
				be.setPath(path);
				return be;
			}
		}

		private class ConstructMenuElement extends AbstractBundleElementConstruct
		{
			public Object construct(Node node)
			{
				node.setType(MenuElement.class);
				String path = getPath(node);
				MenuElement be = new MenuElement(path);
				Construct mappingConstruct = yamlClassConstructors.get(NodeId.mapping);
				mappingConstruct.construct2ndStep(node, be);
				be.setPath(path);
				List<MenuElement> children = be.getChildren();
				for (MenuElement child : children)
				{
					String childPath = child.getPath();
					IPath pathObj = Path.fromOSString(childPath);
					if (!pathObj.isAbsolute())
					{
						// Prepend the bundle directory.
						child.setPath(bundleDirectory.getAbsolutePath() + File.separator + childPath);
					}
				}
				return be;
			}
		}

		private class ConstructProjectTemplateElement extends AbstractBundleElementConstruct
		{
			public Object construct(Node node)
			{
				node.setType(ProjectTemplateElement.class);
				String path = getPath(node);
				ProjectTemplateElement be = new ProjectTemplateElement(path);
				Construct mappingConstruct = yamlClassConstructors.get(NodeId.mapping);
				mappingConstruct.construct2ndStep(node, be);
				be.setPath(path);
				return be;
			}
		}

		private class ConstructEnvironmentElement extends AbstractBundleElementConstruct
		{
			public Object construct(Node node)
			{
				node.setType(EnvironmentElement.class);
				String path = getPath(node);
				EnvironmentElement be = new LazyEnvironmentElement(path);
				Construct mappingConstruct = yamlClassConstructors.get(NodeId.mapping);
				mappingConstruct.construct2ndStep(node, be);
				be.setPath(path);
				return be;
			}
		}

		private class ConstructTemplateElement extends AbstractBundleElementConstruct
		{
			public Object construct(Node node)
			{
				node.setType(TemplateElement.class);
				String path = getPath(node);
				TemplateElement be = new TemplateElement(path);
				Construct mappingConstruct = yamlClassConstructors.get(NodeId.mapping);
				mappingConstruct.construct2ndStep(node, be);
				be.setPath(path);
				return be;
			}
		}

		private class ConstructContentAssistElement extends AbstractBundleElementConstruct
		{
			public Object construct(Node node)
			{
				node.setType(ContentAssistElement.class);
				String path = getPath(node);
				ContentAssistElement be = new ContentAssistElement(path);
				Construct mappingConstruct = yamlClassConstructors.get(NodeId.mapping);
				mappingConstruct.construct2ndStep(node, be);
				be.setPath(path);
				return be;
			}
		}

		private class ConstructSmartTypingPairsElement extends AbstractBundleElementConstruct
		{
			public Object construct(Node node)
			{
				node.setType(SmartTypingPairsElement.class);
				String path = getPath(node);
				SmartTypingPairsElement be = new SmartTypingPairsElement(path);
				Construct mappingConstruct = yamlClassConstructors.get(NodeId.mapping);
				mappingConstruct.construct2ndStep(node, be);
				be.setPath(path);
				return be;
			}
		}
	}

	/**
	 * Lazily loads the real command element from disk when we try to access the invoke string/block or try to execute
	 * it, since that stuff doesn't get serialized.
	 * 
	 * @author cwilliams
	 */
	private class LazyCommandElement extends CommandElement
	{
		private CommandElement real;

		public LazyCommandElement(String path)
		{
			super(path);
		}

		@Override
		public boolean isExecutable()
		{
			// FIXME Should really serialize some value that records what OSes the command has an invoke for so we can
			// tell better if this is executable on this os!
			return true;
		}

		@Override
		public String getInvoke()
		{
			lazyLoad();
			if (real == null)
			{
				return null;
			}
			return real.getInvoke();
		}

		@Override
		public RubyProc getInvokeBlock()
		{
			lazyLoad();
			if (real == null)
			{
				return null;
			}
			return real.getInvokeBlock();
		}

		@Override
		public CommandResult execute(CommandContext context)
		{
			lazyLoad();
			if (real == null)
			{
				return null;
			}
			return real.execute(context);
		}

		@Override
		public Ruby getRuntime()
		{
			lazyLoad();
			if (real == null)
			{
				return null;
			}
			return real.getRuntime();
		}

		@Override
		public CommandContext createCommandContext()
		{
			lazyLoad();
			return new CommandContext(real);
		}

		private synchronized void lazyLoad()
		{
			if (real == null)
			{
				BundleElement owning = getOwningBundle();
				if (owning == null) // we haven't even been attached yet!
				{
					return;
				}
				// remove all elements that are declared in the same file, since they'll end up getting
				// loaded below.
				List<AbstractElement> elements = BundleElement.getElementsByPath(getPath());
				for (AbstractElement element : elements)
				{
					if (element instanceof AbstractBundleElement)
					{
						AbstractBundleElement abe = (AbstractBundleElement) element;
						owning.removeChild(abe);
					}
				}

				// Now load up the file so it really loads into the BundleManager
				BundleManager.getInstance().loadScript(new File(getPath()));

				// Now for whatever code is holding a reference to this, redirect method calls to the
				// real command
				real = owning.getCommandByName(getDisplayName());
			}
		}
	}

	/**
	 * Lazily loads the real environment element from disk when we try to access the invoke block, since that doesn't
	 * get serialized.
	 * 
	 * @author cwilliams
	 */
	private class LazyEnvironmentElement extends EnvironmentElement
	{
		private EnvironmentElement real;

		public LazyEnvironmentElement(String path)
		{
			super(path);
		}

		@Override
		public RubyProc getInvokeBlock()
		{
			lazyLoad();
			return real.getInvokeBlock();
		}

		private synchronized void lazyLoad()
		{
			if (real == null)
			{
				BundleElement owning = getOwningBundle();
				// remove all elements that are declared in the same file, since they'll end up getting
				// loaded below.
				List<AbstractElement> elements = BundleElement.getElementsByPath(getPath());
				for (AbstractElement element : elements)
				{
					if (element instanceof AbstractBundleElement)
					{
						AbstractBundleElement abe = (AbstractBundleElement) element;
						owning.removeChild(abe);
					}
				}

				// Now load up the file so it really loads into the BundleManager
				BundleManager.getInstance().loadScript(new File(getPath()));

				// Now for whatever code is holding a reference to this, redirect method calls to the
				// real command
				List<EnvironmentElement> envs = owning.getEnvs();
				for (EnvironmentElement env : envs)
				{
					if (env.getPath().equals(getPath()))
					{
						real = env;
						break;
					}
				}
			}
		}
	}
}
