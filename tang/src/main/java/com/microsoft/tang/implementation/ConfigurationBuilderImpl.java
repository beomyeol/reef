package com.microsoft.tang.implementation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.Parser;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import com.microsoft.tang.Configuration;
import com.microsoft.tang.ConfigurationBuilder;
import com.microsoft.tang.ExternalConstructor;
import com.microsoft.tang.annotations.Name;
import com.microsoft.tang.exceptions.BindException;
import com.microsoft.tang.exceptions.NameResolutionException;
import com.microsoft.tang.implementation.TypeHierarchy.ClassNode;
import com.microsoft.tang.implementation.TypeHierarchy.NamedParameterNode;
import com.microsoft.tang.implementation.TypeHierarchy.Node;
import com.microsoft.tang.util.ReflectionUtilities;

public class ConfigurationBuilderImpl implements ConfigurationBuilder {
  private final ConfigurationImpl conf;

  ConfigurationBuilderImpl(ConfigurationBuilderImpl t) {
    conf = new ConfigurationImpl();
    try {
      addConfiguration(t);
    } catch (BindException e) {
      throw new IllegalStateException("Could not copy builder", e);
    }
  }

  ConfigurationBuilderImpl() {
    conf = new ConfigurationImpl();
  }

  ConfigurationBuilderImpl(URL... jars) {
    conf = new ConfigurationImpl(jars);
  }

  ConfigurationBuilderImpl(Configuration tang) {
    try {
      conf = new ConfigurationImpl();
      addConfiguration(tang);
    } catch(BindException e) {
      throw new IllegalStateException("Error copying Configuration.", e);
    }
  }
  ConfigurationBuilderImpl(Configuration... tangs) throws BindException {
    conf = new ConfigurationImpl();
    for (Configuration tc : tangs) {
      addConfiguration(((ConfigurationImpl) tc));
    }
  }

  private void addConfiguration(ConfigurationBuilderImpl tc)
      throws BindException {
    addConfiguration(tc.conf);
  }

  @Override
  public void addConfiguration(Configuration ti) throws BindException {
    ConfigurationImpl old = (ConfigurationImpl) ti;
    if (old.dirtyBit) {
      throw new IllegalArgumentException(
          "Cannot copy a dirty ConfigurationBuilderImpl");
    }
    conf.addJars(old.getJars());
    
    for (Class<?> c : old.namespace.getRegisteredClasses()) {
      register(c);
    }
    // Note: The commented out lines would be faster, but, for testing
    // purposes,
    // we run through the high-level bind(), which dispatches to the correct
    // call.
    for (ClassNode<?> cn : old.boundImpls.keySet()) {
      bind(cn.getClazz(), old.boundImpls.get(cn));
      // bindImplementation((Class<?>) cn.getClazz(), (Class)
      // t.boundImpls.get(cn));
    }
    for (ClassNode<?> cn : old.boundConstructors.keySet()) {
      bind(cn.getClazz(), old.boundConstructors.get(cn));
      // bindConstructor((Class<?>) cn.getClazz(), (Class)
      // t.boundConstructors.get(cn));
    }
    for (ClassNode<?> cn : old.singletons) {
      try {
        Class<?> clazz = cn.getClazz();
        Object o = old.singletonInstances.get(cn);
        if(o != null) {
          ClassNode<?> new_cn= (ClassNode<?>)conf.namespace.register(clazz);
          new_cn.setIsSingleton();
          conf.singletons.add(new_cn);
          conf.singletonInstances.put(new_cn, o);
        } else {
          bindSingleton(clazz);
        }
      } catch (BindException e) {
        throw new IllegalStateException(
            "Unexpected BindException when copying ConfigurationBuilderImpl",
            e);
      }
    }
    // The namedParameters set contains the strings that can be used to instantiate new
    // named parameter instances.  Create new ones where we can.
    for (NamedParameterNode<?> np : old.namedParameters.keySet()) {
      bind(np.getNameClass().getName(), old.namedParameters.get(np));
    }
    // Copy references to the remaining (which must have been set with bindVolatileParameter())
    for (NamedParameterNode<?> np : old.namedParameterInstances.keySet()) {
      if(!old.namedParameters.containsKey(np)) {
        Object o = old.namedParameterInstances.get(np);
        NamedParameterNode<?> new_np= (NamedParameterNode<?>)conf.namespace.register(np.getNameClass());
        conf.namedParameterInstances.put(new_np, o);
      }
    }
    for (ClassNode<?> cn : old.legacyConstructors.keySet()) {
      registerLegacyConstructor(cn.getClazz(), old.legacyConstructors.get(cn).constructor.getParameterTypes());
    }
  }

  /**
   * Needed when you want to make a class available for injection, but don't
   * want to bind a subclass to its implementation. Without this call, by the
   * time injector.newInstance() is called, ConfigurationBuilderImpl has been
   * locked down, and the class won't be found.
   * 
   * @param c
   */
  @Override
  public void register(Class<?> c) throws BindException {
    conf.namespace.register(c);
  }
  public void register(String s)  throws BindException {
    try {
      conf.namespace.register(conf.classForName(s));
    } catch(ClassNotFoundException e) {
      throw new BindException("Could not register class", e);
    }
  }

  @Override
  public <T> void registerLegacyConstructor(Class<T> c, final Class<?>... args) throws BindException {
    @SuppressWarnings("unchecked")
    ClassNode<T> cn = (ClassNode<T>) conf.namespace.register(c);
    conf.legacyConstructors.put(cn, cn.createConstructorDef(args));
  }
  
  private Options getCommandLineOptions() {
    Options opts = new Options();
    Collection<NamedParameterNode<?>> namedParameters = conf.namespace
        .getNamedParameterNodes();
    for (NamedParameterNode<?> param : namedParameters) {
      String shortName = param.getShortName();
      if (shortName != null) {
        // opts.addOption(OptionBuilder.withLongOpt(shortName).hasArg()
        // .withDescription(param.toString()).create());
        opts.addOption(shortName, true, param.toString());
      }
    }
    for (Option o : applicationOptions.keySet()) {
      opts.addOption(o);
    }
    return opts;
  }

  public interface CommandLineCallback {
    public void process(Option option);
  }

  Map<Option, CommandLineCallback> applicationOptions = new HashMap<Option, CommandLineCallback>();

  @Override
  public void addCommandLineOption(Option option, CommandLineCallback cb) {
    // TODO: Check for conflicting options.
    applicationOptions.put(option, cb);
  }

  @Override
  public <T> void processCommandLine(String[] args) throws IOException,
      BindException {
    Options o = getCommandLineOptions();
    Option helpFlag = new Option("?", "help");
    o.addOption(helpFlag);
    Parser g = new GnuParser();
    CommandLine cl;
    try {
      cl = g.parse(o, args);
    } catch (ParseException e) {
      throw new IOException("Could not parse config file", e);
    }
    if (cl.hasOption("?")) {
      HelpFormatter help = new HelpFormatter();
      help.printHelp("reef", o);
      return;
    }
    for (Object ob : o.getOptions()) {
      Option option = (Option) ob;
      String shortName = option.getOpt();
      String value = option.getValue();
      // System.out.println("Got option " + shortName + " = " + value);
      // if(cl.hasOption(shortName)) {

      NamedParameterNode<T> n = conf.namespace.getNodeFromShortName(shortName);
      if (n != null && value != null) {
        // XXX completely untested.

        if (applicationOptions.containsKey(option)) {
          applicationOptions.get(option).process(option);
        } else {
          bindNamedParameter(n.clazz, value);
        }
      }
    }
  }

  @Override
  public <T> void bind(String key, String value) throws BindException {
    if (conf.sealed)
      throw new IllegalStateException(
          "Can't bind to sealed ConfigurationBuilderImpl!");
    Node n;
    try {
      n = conf.namespace.register(conf.classForName(key));
    } catch(ClassNotFoundException e) {
      throw new BindException("Could not find class " + key);
    }
    /*
     * String longVal = shortNames.get(value); if (longVal != null) value =
     * longVal;
     */
    if (n instanceof NamedParameterNode) {
      bindParameter((NamedParameterNode<?>) n, value);
    } else if (n instanceof ClassNode) {
      Class<?> v;
      try {
        v = conf.classForName(value);
      } catch(ClassNotFoundException e) {
        throw new BindException("Could not find class " + value);
      }
      bind(((ClassNode<?>) n).getClazz(), v);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> void bind(Class<T> c, Class<?> val) throws BindException {
    if (ExternalConstructor.class.isAssignableFrom(val)
        && (!ExternalConstructor.class.isAssignableFrom(c))) {
      bindConstructor(c,
          (Class<? extends ExternalConstructor<? extends T>>) val);
    } else {
      bindImplementation(c, (Class<? extends T>) val);
    }
  }

  @Override
  public <T> void bindImplementation(Class<T> c, Class<? extends T> d)
      throws BindException {
    if (conf.sealed)
      throw new IllegalStateException(
          "Can't bind to sealed ConfigurationBuilderImpl!");
    if (!c.isAssignableFrom(d)) {
      throw new ClassCastException(d.getName()
          + " does not extend or implement " + c.getName());
    }

    Node n = conf.namespace.register(c);
    conf.namespace.register(d);

    if (n instanceof ClassNode) {
      conf.boundImpls.put((ClassNode<?>) n, d);
    } else {
      throw new BindException(
          "Detected type mismatch.  bindImplementation needs a ClassNode, but "
              + "namespace contains a " + n);
    }
  }

  private <T> void bindParameter(NamedParameterNode<T> name, String value) {
    if (conf.sealed)
      throw new IllegalStateException(
          "Can't bind to sealed ConfigurationBuilderImpl!");
    T o = ReflectionUtilities.parse(name.argClass, value);
    conf.namedParameters.put(name, value);
    conf.namedParameterInstances.put(name, o);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> void bindNamedParameter(Class<? extends Name<T>> name, String s)
      throws BindException {
    if (conf.sealed)
      throw new IllegalStateException(
          "Can't bind to sealed ConfigurationBuilderImpl!");
    Node np = conf.namespace.register(name);
    if (np instanceof NamedParameterNode) {
      bindParameter((NamedParameterNode<T>) np, s);
    } else {
      throw new BindException(
          "Detected type mismatch when setting named parameter " + name
              + "  Expected NamedParameterNode, but namespace contains a " + np);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> void bindSingleton(Class<T> c) throws BindException {
    if (conf.sealed)
      throw new IllegalStateException(
          "Can't bind to sealed ConfigurationBuilderImpl!");

    Node n = conf.namespace.register(c);

    if (!(n instanceof ClassNode)) {
      throw new IllegalArgumentException("Can't bind singleton to " + n
          + " try bindParameter() instead.");
    }
    ClassNode<T> cn = (ClassNode<T>) n;
    cn.setIsSingleton();
    conf.singletons.add(cn);
  }

  @Override
  public <T> void bindSingletonImplementation(Class<T> c, Class<? extends T> d)
        throws BindException {
      bindSingleton(c);
      bindImplementation(c, d);
  }

  @Override
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public <T> void bindConstructor(Class<T> c,
      Class<? extends ExternalConstructor<? extends T>> v) throws BindException {

    conf.namespace.register(v);
    try {
      conf.boundConstructors.put((ClassNode<?>) conf.namespace.register(c),
          (Class) v);
    } catch (ClassCastException e) {
      throw new IllegalArgumentException(
          "Cannot register external class constructor for " + c
              + " (which is probably a named parameter)");
    }
  }
  
  @Override
  public ConfigurationImpl build() {
    ConfigurationBuilderImpl b = new ConfigurationBuilderImpl(this);
    return b.conf;
  }

  @Override
  public void addConfiguration(File file) throws IOException, BindException {
    PropertiesConfiguration confFile;
    try {
      confFile = new PropertiesConfiguration(file);
    } catch (ConfigurationException e) {
      throw new BindException("Problem parsing config file", e);
    }
    processConfigFile(confFile);
  }
  
  @Override
  public final void addConfiguration(String conf) throws BindException {
	  try {
		File tmp = File.createTempFile("tang", "tmp");
		FileOutputStream fos = new FileOutputStream(tmp);
		fos.write(conf.getBytes());
		fos.close();
		addConfiguration(tmp);
		tmp.delete();
	} catch (IOException e) {
		e.printStackTrace();
	}
  }
  
  
  public void processConfigFile(PropertiesConfiguration confFile) throws IOException, BindException {
    Iterator<String> it = confFile.getKeys();
    Map<String, String> shortNames = new HashMap<String, String>();

    while (it.hasNext()) {
      String key = it.next();
      String longName = shortNames.get(key);
      String[] values = confFile.getStringArray(key);
      if (longName != null) {
        // System.err.println("Mapped " + key + " to " + longName);
        key = longName;
      }
      for (String value : values) {
        boolean isSingleton = false;
        if (value.equals(ConfigurationImpl.SINGLETON)) {
          isSingleton = true;
        }
        if (value.equals(ConfigurationImpl.REGISTERED)) {
          try {
            this.conf.namespace.register(conf.classForName(key));
          } catch (ClassNotFoundException e) {
            throw new BindException("Could not find class " + key
                + " from config file", e);
          }
        } else if (key.equals(ConfigurationImpl.IMPORT)) {
          if (isSingleton) {
            throw new IllegalArgumentException("Can't "
                + ConfigurationImpl.IMPORT + "=" + ConfigurationImpl.SINGLETON
                + ".  Makes no sense");
          }
          try {
            this.conf.namespace.register(conf.classForName(value));
            String[] tok = value.split(ReflectionUtilities.regexp);
            try {
              this.conf.namespace.getNode(tok[tok.length - 1]);
              throw new IllegalArgumentException("Conflict on short name: "
                  + tok[tok.length - 1]);
            } catch (NameResolutionException e) {
              String oldValue = shortNames.put(tok[tok.length - 1], value);
              if (oldValue != null) {
                throw new IllegalArgumentException("Name conflict.  "
                    + tok[tok.length - 1] + " maps to " + oldValue + " and "
                    + value);
              }
            }
          } catch (ClassNotFoundException e) {
            throw new BindException("Could not find class " + value
                + " in config file", e);
          }
        } else if(value.startsWith(ConfigurationImpl.INIT)) {
          String parseValue = value.substring(ConfigurationImpl.INIT.length(), value.length());
          parseValue = parseValue.replaceAll("^[\\s\\(]+", "");
          parseValue = parseValue.replaceAll("[\\s\\)]+$", "");
          String[] classes = parseValue.split("[\\s\\-]+");
          Class<?>[] clazzes = new Class[classes.length];
          for(int i = 0; i < classes.length; i++) {
            try {
              clazzes[i] = conf.classForName(classes[i]);
            } catch (ClassNotFoundException e) {
              throw new BindException("Could not find arg " + classes[i] + " of constructor for " + key);
            }
          }
          try {
            registerLegacyConstructor(conf.classForName(key), clazzes);
          } catch (ClassNotFoundException e) {
            throw new BindException("Could not find class " + key + " when trying to register legacy constructor " + value);
          }
        } else {
          if (isSingleton) {
            final Class<?> c;
            try {
              c = conf.classForName(key);
            } catch (ClassNotFoundException e) {
              throw new BindException(
                  "Could not find class to be bound as singleton", e);
            }
            bindSingleton(c);
          } else {
            bind(key, value);
          }
        }
      }
    }
  }
}
