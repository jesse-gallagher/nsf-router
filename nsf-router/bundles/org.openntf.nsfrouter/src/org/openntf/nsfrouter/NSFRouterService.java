/*
 * Copyright © 2021 Jesse Gallagher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openntf.nsfrouter;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.servlet.ServletException;

import com.ibm.designer.domino.napi.NotesAPIException;
import com.ibm.designer.domino.napi.NotesDatabase;
import com.ibm.designer.domino.napi.NotesNote;
import com.ibm.designer.domino.napi.NotesSession;
import com.ibm.designer.domino.napi.design.FileAccess;
import com.ibm.designer.runtime.domino.adapter.ComponentModule;
import com.ibm.designer.runtime.domino.adapter.HttpService;
import com.ibm.designer.runtime.domino.adapter.LCDEnvironment;
import com.ibm.designer.runtime.domino.bootstrap.adapter.HttpServletRequestAdapter;
import com.ibm.designer.runtime.domino.bootstrap.adapter.HttpServletResponseAdapter;
import com.ibm.designer.runtime.domino.bootstrap.adapter.HttpSessionAdapter;
import com.ibm.designer.runtime.domino.bootstrap.util.StringUtil;

public class NSFRouterService extends HttpService {
	
	private NotesSession session;
	private final Map<String, Long> lastChecked = new HashMap<>();
	private final Map<String, Map<Pattern, String>> nsfRoutes = new HashMap<>();

	public NSFRouterService(LCDEnvironment env) {
		super(env);
		
		this.session = new NotesSession();
	}
	
	@Override
	public synchronized boolean isXspUrl(String pathInfo, boolean arg1) {
		// TODO improve threading
		if(StringUtil.isEmpty(pathInfo)) {
			return false;
		}
		
		// Check to see if it's potentially within an NSF at all
		int nsfIndex = pathInfo.indexOf(".nsf");
		if(nsfIndex < 2) {
			return false;
		}

		String nsfName = pathInfo.substring(1, nsfIndex+4);
		
		try {
			if(!session.databaseExists(nsfName)) {
				// TODO see if it's cheaper to just do the design-mod check below and let it throw an exception
				return false;
			}
			
			long designMod = NotesSession.getLastNonDataModificationDateByName(nsfName);
			if(!lastChecked.containsKey(nsfName) || lastChecked.get(nsfName) < designMod) {
				// Refresh the list from the design
				NotesDatabase database = session.getDatabase(nsfName);
				try {
					database.open();
					NotesNote routesFile = FileAccess.getFileByPath(database, "nsfrouter.properties");
					if(routesFile != null) {
						Properties routesProps = new Properties();
						try(InputStream is = FileAccess.readFileContentAsInputStream(routesFile)) {
							routesProps.load(is);
						}
						this.nsfRoutes.put(nsfName, routesProps.entrySet().stream()
							.collect(Collectors.toMap(
								entry -> {
									String pattern = (String)entry.getKey();
									if(!pattern.startsWith("/")) {
										pattern = "/" + pattern;
									}
									return Pattern.compile(pattern);
								},
								entry -> {
									String target = (String)entry.getValue();
									if(!target.startsWith("/")) {
										return "/" + target;
									} else {
										return target;
									}
								}
							)));
					} else {
						this.nsfRoutes.put(nsfName, null);
					}
				} finally {
					database.recycle();
				}
			}
		} catch(NotesAPIException e) {
			switch(e.getNativeErrorCode()) {
			case 0x0103: // ERR_NOEXIST
				// Ignore
				break;
			default:
				e.printStackTrace();
			}
		} catch(Throwable t) {
			t.printStackTrace();
		}
		
		Map<Pattern, String> routes = this.nsfRoutes.get(nsfName);
		if(routes != null && !routes.isEmpty()) {
			// Check if any routes match
			int queryIndex = pathInfo.indexOf('?');
			String nsfPathInfo;
			if(queryIndex > 0) {
				nsfPathInfo = pathInfo.substring(nsfIndex+4, queryIndex);
			} else {
				nsfPathInfo = pathInfo.substring(nsfIndex+4);
			}
			
			// TODO consider caching the entry that matches it so that we don't have to re-do the check in doService
			return routes.keySet()
				.stream()
				.anyMatch(p -> p.matcher(nsfPathInfo).matches());
		}
		
		return false;
	}

	@Override
	public boolean doService(String arg0, String pathInfo, HttpSessionAdapter httpSession, HttpServletRequestAdapter servletRequest,
			HttpServletResponseAdapter servletResponse) throws ServletException, IOException {
		
		// Check if this actually applies to us
		int nsfIndex = pathInfo.indexOf(".nsf");
		if(nsfIndex < 2) {
			return false;
		}

		String nsfName = pathInfo.substring(1, nsfIndex+4);
		Map<Pattern, String> routes = this.nsfRoutes.get(nsfName);
		if(routes != null && !routes.isEmpty()) {
			// Check if any routes match
			String nsfPathInfo = pathInfo.substring(nsfIndex+4);
			
			for(Map.Entry<Pattern, String> route : routes.entrySet()) {
				Matcher matcher = route.getKey().matcher(nsfPathInfo);
				if(matcher.matches()) {
					// Build our new path
					StringBuilder newPath = new StringBuilder();
					newPath.append(pathInfo.substring(0, nsfIndex+4));
					newPath.append(nsfPathInfo.replaceAll(route.getKey().pattern(), route.getValue()));
					String query = servletRequest.getQueryString();
					if(StringUtil.isNotEmpty(query)) {
						if(newPath.indexOf("?") > -1) {
							newPath.append("&");
						} else {
							newPath.append("?");
						}
						newPath.append(query);
					}
					// TODO consider handing this off to NSFService directly when it's an XSP URL
					servletResponse.sendRedirect(newPath.toString());
					return true;
				}
			}
		}
		
		return false;
	}
	
	@Override
	public void destroyService() {
		super.destroyService();
		
		try {
			session.recycle();
		} catch (NotesAPIException e) {
			// ???
		}
	}

	@Override
	public void getModules(List<ComponentModule> modules) {
		// NOP
	}

}
