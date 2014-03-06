package entity_linking;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Comparator;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.Vector;

public class Utils {
	private static HashMap<String,String> wikiRedirects = null;
	
	public static void loadWikiRedirects(String filename) throws IOException {
       System.err.println("[INFO] Loading Wikipedia redirects...");

		wikiRedirects = new HashMap<String,String>(3000000);
		
		if (new java.io.File(filename).exists() == false) {
			System.err.println("[FATAL] Wikipedia Redirects file does not exist.");
			System.exit(1);
		}
		
		BufferedReader in = new BufferedReader(new FileReader(filename));
		String line = in.readLine();
		while (line != null) {
			StringTokenizer st = new StringTokenizer(line, "\t");
			wikiRedirects.put(st.nextToken(), st.nextToken());
			line = in.readLine();
		}
		System.err.println("[INFO] Done loading Wikipedia redirects.");
	}
	
	public static String pruneURL(String s) {
	    if (wikiRedirects == null) {
	        System.err.println("[FATAL] The Wikipedia redirects file was not loaded.");
	        System.exit(1);
	    }
		String url = s.trim();
		if (url.contains("wiki/")) {
			url = url.substring(url.lastIndexOf("wiki/") + "wiki/".length());
		}
		if (url.contains("wikipedia.org")) {
			url = url.substring(url.indexOf("wikipedia.org") + "wikipedia.org".length());
		}
		String final_url = url;
		if (url.length() == 0) return "";

		if (Character.isLowerCase(url.charAt(0))) {
		    url = url.substring(0, 1).toUpperCase() + url.substring(1);
		}

		try {
			final_url = URLDecoder.decode(url, "UTF-8");
		} catch (UnsupportedEncodingException e) {
		} catch (java.lang.IllegalArgumentException e) {
	    }	

		final_url = final_url.replace("%29", ")");
        final_url = final_url.replace("%28", "(");

		final_url = final_url.replace('_', ' ');
		if (wikiRedirects.containsKey(final_url)) {
			final_url = wikiRedirects.get(final_url);
		}
		
		final_url = final_url.replace(' ', '_');
		StringBuilder sb = new StringBuilder();
		for (char c : final_url.toCharArray()) {
			if (!Character.isSpace(c)) {
				sb.append(c);
			}
		}
		return sb.toString();
	}
	
	// Comparator used for mentions_hashtable.
	static class StringComp implements Comparator<String> {
		@Override
		public int compare(String o1, String o2) {
			// Try to match longer anchor texts first. For example: "University of Oklahoma" will be replaced
			// with its anchor before it will be "Oklahoma".
			if (o1.length() - o2.length() != 0) {
			    return o2.length() - o1.length();
			}
			return o2.compareTo(o1);
		}
	}
	
	static 	public boolean isWordSeparator(char c) {
		if (Character.isWhitespace(c) || Character.isSpace(c) || c == ' ' || c == ',' || c == '"' || c == ':' || c == '.' || c == '-' ||  c == '?' || c == '!' || c == '(' ||
				c == ')' || c == '[' || c == ']' || c == '+' || c == '*' || c == '=' || c == '\'' || c == '`' || c == '\n' ||
				c == '\r' || c == ';' || c =='#') {
		    return true;
		}
		return false;
	}
	
	// Returns all sub-token spans of t=n-,n,n+ that contain n.
	// n is the token starting at offset from text.
	static public Vector<TokenSpan> getTokenSpans(String text, int offset, int length) {
		Vector<TokenSpan> spans = new Vector<TokenSpan>();
		
		if (offset < 0 || offset >= text.length()) {
		    return spans;
		}
		int startN = offset;
		int endN = offset + length - 1;
		
		// Start index of n-
		int i = startN - 1;
		while (i >= 0 && isWordSeparator(text.charAt(i))) {
		    i--;
		}

        int startMinusN = startN;
		if (i >= 0) {
		    while (i >= 0 && !isWordSeparator(text.charAt(i))) {
	            i--;
	        }
		    startMinusN = i+1;
		}
		
	    // End index of the last char of n+
        i = endN + 1;
        while (i < text.length() && isWordSeparator(text.charAt(i))) {
            i++;
        }

        int endPlusN = endN;
        if (i < text.length()) {
            while (i < text.length() && !isWordSeparator(text.charAt(i))) {
                i++;
            }
            endPlusN = i-1;
        }
		
		// n
		spans.add(new TokenSpan(startN, endN+1));
		if (startMinusN < startN) {
			// n-,n
			spans.add(new TokenSpan(startMinusN, endN+1));			
		}
		if (endPlusN > endN) {
			// n,n+
			spans.add(new TokenSpan(startN, endPlusN+1));			
		}
		if (endPlusN > endN && startMinusN < startN) {
			// n-,n,n+
			spans.add(new TokenSpan(startMinusN, endPlusN+1));			
		}
		
		return spans;
	}
}