/**
 * Copyright (c) 2014, the Railo Company Ltd.
 * Copyright (c) 2015, Lucee Assosication Switzerland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either 
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public 
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package lucee.runtime.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import org.osgi.framework.BundleException;
import org.osgi.framework.Version;
import org.xml.sax.SAXException;

import lucee.commons.io.CharsetUtil;
import lucee.commons.io.IOUtil;
import lucee.commons.io.SystemUtil;
import lucee.commons.io.log.Log;
import lucee.commons.io.log.LogUtil;
import lucee.commons.io.res.Resource;
import lucee.commons.lang.StringUtil;
import lucee.loader.engine.CFMLEngine;
import lucee.runtime.config.XMLConfigReader.NameRule;
import lucee.runtime.config.XMLConfigReader.ReadRule;
import lucee.runtime.converter.ConverterException;
import lucee.runtime.converter.JSONConverter;
import lucee.runtime.converter.JSONDateFormat;
import lucee.runtime.engine.InfoImpl;
import lucee.runtime.engine.ThreadLocalPageContext;
import lucee.runtime.exp.PageException;
import lucee.runtime.interpreter.JSONExpressionInterpreter;
import lucee.runtime.listener.SerializationSettings;
import lucee.runtime.op.Caster;
import lucee.runtime.osgi.OSGiUtil;
import lucee.runtime.type.Array;
import lucee.runtime.type.Collection;
import lucee.runtime.type.Collection.Key;
import lucee.runtime.type.KeyImpl;
import lucee.runtime.type.Struct;
import lucee.runtime.type.util.KeyConstants;

public abstract class ConfigFactory {

	public static final int NEW_NONE = 0;
	public static final int NEW_MINOR = 1;
	public static final int NEW_FRESH = 2;
	public static final int NEW_FROM4 = 3;

	public static UpdateInfo getNew(CFMLEngine engine, Resource contextDir, final boolean readOnly, UpdateInfo defaultValue) {
		try {
			return getNew(engine, contextDir, readOnly);
		}
		catch (Exception e) {
			return defaultValue;
		}
	}

	public static UpdateInfo getNew(CFMLEngine engine, Resource contextDir, final boolean readOnly) throws IOException, BundleException {
		lucee.Info info = engine.getInfo();

		String strOldVersion;
		final Resource resOldVersion = contextDir.getRealResource("version");
		String strNewVersion = info.getVersion() + "-" + info.getRealeaseTime();
		// fresh install
		if (!resOldVersion.exists()) {
			if (!readOnly) {
				resOldVersion.createNewFile();
				IOUtil.write(resOldVersion, strNewVersion, SystemUtil.getCharset(), false);
			}
			return UpdateInfo.NEW_FRESH;
		}
		// changed version
		else if (!(strOldVersion = IOUtil.toString(resOldVersion, SystemUtil.getCharset())).equals(strNewVersion)) {
			if (!readOnly) {
				IOUtil.write(resOldVersion, strNewVersion, SystemUtil.getCharset(), false);
			}
			Version oldVersion = OSGiUtil.toVersion(strOldVersion);

			return new UpdateInfo(oldVersion, oldVersion.getMajor() < 5 ? NEW_FROM4 : NEW_MINOR);
		}
		return UpdateInfo.NEW_NONE;
	}

	public static class UpdateInfo {

		public static final UpdateInfo NEW_NONE = new UpdateInfo(ConfigWebFactory.NEW_NONE);
		public static final UpdateInfo NEW_FRESH = new UpdateInfo(ConfigWebFactory.NEW_FRESH);

		public final Version oldVersion;
		public final int updateType;

		public UpdateInfo(int updateType) {
			this.oldVersion = null;
			this.updateType = updateType;
		}

		public UpdateInfo(Version oldVersion, int updateType) {
			this.oldVersion = oldVersion;
			this.updateType = updateType;
		}

		public String getUpdateTypeAsString() {
			if (updateType == ConfigWebFactory.NEW_NONE) return "new-none";
			if (updateType == ConfigWebFactory.NEW_FRESH) return "new-fresh";
			if (updateType == ConfigWebFactory.NEW_FROM4) return "new-from4";
			if (updateType == ConfigWebFactory.NEW_MINOR) return "new-minor";
			return "unkown:" + updateType;
		}

	}

	public static void updateRequiredExtension(CFMLEngine engine, Resource contextDir, Log log) {
		lucee.Info info = engine.getInfo();
		try {
			Resource res = contextDir.getRealResource("required-extension");
			String str = info.getVersion() + "-" + info.getRealeaseTime();
			if (!res.exists()) res.createNewFile();
			IOUtil.write(res, str, SystemUtil.getCharset(), false);

		}
		catch (Exception e) {
			if (log != null) log.error("required-extension", e);
		}
	}

	public static boolean isRequiredExtension(CFMLEngine engine, Resource contextDir, Log log) {
		lucee.Info info = engine.getInfo();
		try {
			Resource res = contextDir.getRealResource("required-extension");
			if (!res.exists()) return false;

			String writtenVersion = IOUtil.toString(res, SystemUtil.getCharset());
			String currVersion = info.getVersion() + "-" + info.getRealeaseTime();
			return writtenVersion.equals(currVersion);
		}
		catch (Exception e) {
			if (log != null) log.error("required-extension", e);
		}
		return false;
	}

	/**
	 * load XML Document from XML File
	 * 
	 * @param xmlFile XML File to read
	 * @return returns the Document
	 * @throws SAXException
	 * @throws IOException
	 * @throws PageException
	 */
	static Struct loadDocument(Resource file) throws SAXException, IOException, PageException {
		InputStream is = null;
		try {
			return _loadDocument(file);
		}
		finally {
			IOUtil.close(is);
		}
	}

	static Struct loadDocumentCreateIfFails(Resource configFile, String type) throws SAXException, IOException, PageException {
		try {
			return _loadDocument(configFile);
		}
		catch (Exception e) {
			// rename buggy config files
			if (configFile.exists()) {
				LogUtil.log(ThreadLocalPageContext.getConfig(), Log.LEVEL_INFO, ConfigFactory.class.getName(),
						"Config file [" + configFile + "] was not valid and has been replaced");
				LogUtil.log(ThreadLocalPageContext.getConfig(), ConfigFactory.class.getName(), e);
				int count = 1;
				Resource bugFile;
				Resource configDir = configFile.getParentResource();
				while ((bugFile = configDir.getRealResource("lucee-" + type + "." + (count++) + ".buggy")).exists()) {}
				IOUtil.copy(configFile, bugFile);
				configFile.delete();
			}
			createConfigFile(type, configFile);
			return loadDocument(configFile);
		}
	}

	public static void translateConfigFile(Resource configFileOld, Resource configFileNew) throws ConverterException, IOException, SAXException {
		// read the old config (XML)
		Struct root = ConfigWebUtil.getAsStruct("cfLuceeConfiguration", new XMLConfigReader(configFileOld, true, new ReadRule(), new NameRule()).getData());

		//////////////////// charset ////////////////////
		{
			Struct charset = ConfigWebUtil.getAsStruct("charset", root);
			Struct regional = ConfigWebUtil.getAsStruct("regional", root);
			Struct fileSystem = ConfigWebUtil.getAsStruct("fileSystem", root);
			copy("charset", "templateCharset", fileSystem, root);// deprecated but still supported
			copy("encoding", "templateCharset", fileSystem, root);// deprecated but still supported
			move("templateCharset", charset, root);

			move("charset", "webCharset", charset, root);// deprecated but still supported
			copy("encoding", "webCharset", fileSystem, root);// deprecated but still supported
			copy("defaultEncoding", "webCharset", regional, root);// deprecated but still supported
			move("webCharset", charset, root);

			copy("charset", "resourceCharset", fileSystem, root);// deprecated but still supported
			copy("encoding", "resourceCharset", fileSystem, root);// deprecated but still supported
			move("resourceCharset", charset, root);

			rem("charset", root);
		}
		//////////////////// regional ////////////////////
		{
			Struct regional = ConfigWebUtil.getAsStruct("regional", root);
			move("timezone", regional, root);
			move("locale", regional, root);
			move("timeserver", regional, root);
			move("useTimeserver", regional, root);
			rem("regional", root);
		}
		//////////////////// application ////////////////////
		{
			Struct application = ConfigWebUtil.getAsStruct("application", root);
			Struct scope = ConfigWebUtil.getAsStruct("scope", root);
			move("listenerType", application, root);
			move("listenerMode", application, root);
			move("typeChecking", application, root);
			move("cachedAfter", application, root);
			for (String type: ConfigWebFactory.STRING_CACHE_TYPES) {
				move("cachedWithin" + StringUtil.ucFirst(type), application, root);
			}
			move("allowUrlRequesttimeout", "requestTimeoutInURL", application, root);
			move("requesttimeout", "requestTimeout", scope, root);// deprecated but still supported
			move("requesttimeout", "requestTimeout", application, root);
			move("scriptProtect", application, root);
			move("classicDateParsing", application, root);
			move("cacheDirectory", application, root);
			move("cacheDirectoryMaxSize", application, root);
			move("adminSynchronisation", "adminSync", application, root);
			move("adminSync", application, root);

			rem("application", root);
		}

		//////////////////// caches ////////////////////
		{
			Struct cache = ConfigWebUtil.getAsStruct("cache", root);
			Struct caches = ConfigWebUtil.getAsStruct("caches", root);
			Array conns = ConfigWebUtil.getAsArray("connection", cache);

			// classes
			move("cache", "cacheClasses", caches, root);

			// defaults
			for (String type: ConfigWebFactory.STRING_CACHE_TYPES_MAX) {
				move("default" + StringUtil.ucFirst(type), cache, root);
			}
			// connections
			Iterator<?> it = conns.getIterator();
			while (it.hasNext()) {
				Struct conn = Caster.toStruct(it.next(), null);
				if (conn == null) continue;
				add(conn, Caster.toString(conn.remove(KeyConstants._name, null), null), caches);
			}
			rem("cache", root);
		}

		//////////////////// cache handlers ////////////////////
		{
			Struct handlers = ConfigWebUtil.getAsStruct("cacheHandlers", root);
			Array handler = ConfigWebUtil.getAsArray("cacheHandler", handlers);

			Key[] keys = handler.keys();
			for (int i = keys.length - 1; i >= 0; i--) {
				Key k = keys[i];
				Struct data = Caster.toStruct(handler.get(k, null), null);
				if (data == null) continue;
				add(data, Caster.toString(data.remove(KeyConstants._id, null), null), handlers);
				handler.remove(k, null);
			}
		}

		//////////////////// CFX ////////////////////
		{
			Struct extTags = ConfigWebUtil.getAsStruct("extTags", root);
			Array extTag = ConfigWebUtil.getAsArray("extTag", extTags);
			Struct cfx = ConfigWebUtil.getAsStruct("cfx", root);

			Iterator<?> it = extTag.getIterator();
			while (it.hasNext()) {
				Struct conn = Caster.toStruct(it.next(), null);
				if (conn == null) continue;
				add(conn, Caster.toString(conn.remove(KeyConstants._name, null), null), cfx);
			}
			rem("extTags", root);
		}

		//////////////////// Compiler ////////////////////
		{
			Struct compiler = ConfigWebUtil.getAsStruct("compiler", root);
			move("supressWsBeforeArg", "suppressWhitespaceBeforeArgument", compiler, root);// deprecated but still supported
			move("suppressWsBeforeArg", "suppressWhitespaceBeforeArgument", compiler, root);
			move("dotNotationUpperCase", compiler, root);
			move("fullNullSupport", "nullSupport", compiler, root);
			move("defaultFunctionOutput", compiler, root);
			move("externalizeStringGte", compiler, root);
			move("allowLuceeDialect", compiler, root);
			move("handleUnquotedAttributeValueAsString", compiler, root);
			rem("compiler", root);
		}

		remIfEmpty(root);

		// TODO scope?
		//////////////////// translate ////////////////////
		// allowLuceeDialect,cacheDirectory,cacheDirectoryMaxSize,
		// classicDateParsing,cacheClasses,cacheHandlers,cfx,defaultFunctionOutput,externalizeStringGte,handleUnquotedAttributeValueAsString

		// store it as Json
		JSONConverter json = new JSONConverter(true, CharsetUtil.UTF8, JSONDateFormat.PATTERN_CF, true, true);
		String str = json.serialize(null, root, SerializationSettings.SERIALIZE_AS_ROW);
		IOUtil.write(configFileNew, str, CharsetUtil.UTF8, false);
	}

	private static void remIfEmpty(Collection coll) {
		Key[] keys = coll.keys();
		Object v;
		Collection sub;
		for (Key k: keys) {
			v = coll.get(k, null);
			if (v instanceof Collection) {
				sub = (Collection) v;
				if (sub.size() > 0) remIfEmpty(sub);
				if (sub.size() == 0) coll.remove(k, null);
			}
		}
	}

	private static void rem(String key, Struct sct) {
		sct.remove(KeyImpl.init(key), null);
	}

	private static void move(String key, Struct from, Struct to) {
		Key k = KeyImpl.init(key);
		Object val = from.remove(k, null);
		if (val != null) to.setEL(k, val);
	}

	private static void move(String fromKey, String toKey, Struct from, Struct to) {
		Object val = from.remove(KeyImpl.init(fromKey), null);
		if (val != null) to.setEL(KeyImpl.init(toKey), val);
	}

	private static void add(Object fromData, String toKey, Struct to) {
		if (fromData == null) return;
		to.setEL(KeyImpl.init(toKey), fromData);
	}

	private static void copy(String fromKey, String toKey, Struct from, Struct to) {
		Object val = from.get(KeyImpl.init(fromKey), null);
		if (val != null) to.setEL(KeyImpl.init(toKey), val);
	}

	/**
	 * creates the Config File, if File not exist
	 * 
	 * @param xmlName
	 * @param configFile
	 * @throws IOException
	 */
	static void createConfigFile(String name, Resource configFile) throws IOException {
		createFileFromResource("/resource/config/" + name + ".json", configFile.getAbsoluteResource());
	}

	private static Struct _loadDocument(Resource res) throws SAXException, IOException, PageException {
		String name = res.getName();
		// That step is not necessary anymore TODO remove
		if (StringUtil.endsWithIgnoreCase(name, ".xml.cfm") || StringUtil.endsWithIgnoreCase(name, ".xml")) {
			return ConfigWebUtil.getAsStruct("cfLuceeConfiguration", new XMLConfigReader(res, true, new ReadRule(), new NameRule()).getData());
		}
		return Caster.toStruct(new JSONExpressionInterpreter().interpret(null, IOUtil.toString(res, CharsetUtil.UTF8)));
	}

	/**
	 * creates a File and his content froma a resurce
	 * 
	 * @param resource
	 * @param file
	 * @param password
	 * @throws IOException
	 */
	static void createFileFromResource(String resource, Resource file, String password) throws IOException {
		LogUtil.logGlobal(ThreadLocalPageContext.getConfig(), Log.LEVEL_INFO, ConfigFactory.class.getName(), "Write file: [" + file + "]");
		if (file.exists()) file.delete();

		InputStream is = InfoImpl.class.getResourceAsStream(resource);
		if (is == null) throw new IOException("File [" + resource + "] does not exist.");
		file.createNewFile();
		IOUtil.copy(is, file, true);
	}

	/**
	 * creates a File and his content froma a resurce
	 * 
	 * @param resource
	 * @param file
	 * @throws IOException
	 */
	static void createFileFromResource(String resource, Resource file) throws IOException {
		createFileFromResource(resource, file, null);
	}

	public static void createFileFromResourceEL(String resource, Resource file) {
		try {
			createFileFromResource(resource, file, null);
		}
		catch (Exception e) {
			LogUtil.logGlobal(ThreadLocalPageContext.getConfig(), ConfigFactory.class.getName(), e);
		}
	}

	static void create(String srcPath, String[] names, Resource dir, boolean doNew) {
		for (int i = 0; i < names.length; i++) {
			create(srcPath, names[i], dir, doNew);
		}
	}

	static Resource create(String srcPath, String name, Resource dir, boolean doNew) {
		if (!dir.exists()) dir.mkdirs();

		Resource f = dir.getRealResource(name);
		if (!f.exists() || doNew) ConfigFactory.createFileFromResourceEL(srcPath + name, f);
		return f;

	}

	static void delete(Resource dbDir, String[] names) {
		for (int i = 0; i < names.length; i++) {
			delete(dbDir, names[i]);
		}
	}

	static void delete(Resource dbDir, String name) {
		Resource f = dbDir.getRealResource(name);
		if (f.exists()) {
			LogUtil.logGlobal(ThreadLocalPageContext.getConfig(), Log.LEVEL_INFO, ConfigFactory.class.getName(), "Delete file: [" + f + "]");

			f.delete();
		}

	}

}
