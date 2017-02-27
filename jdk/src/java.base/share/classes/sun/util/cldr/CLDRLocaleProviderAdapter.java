/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.util.cldr;

import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.text.spi.BreakIteratorProvider;
import java.text.spi.CollatorProvider;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import sun.util.locale.provider.JRELocaleProviderAdapter;
import sun.util.locale.provider.LocaleProviderAdapter;
import sun.util.locale.provider.LocaleDataMetaInfo;

/**
 * LocaleProviderAdapter implementation for the CLDR locale data.
 *
 * @author Masayoshi Okutsu
 * @author Naoto Sato
 */
public class CLDRLocaleProviderAdapter extends JRELocaleProviderAdapter {

    private static final CLDRBaseLocaleDataMetaInfo baseMetaInfo = new CLDRBaseLocaleDataMetaInfo();
    // Assumption: CLDR has only one non-Base module.
    private final LocaleDataMetaInfo nonBaseMetaInfo;

    // parent locales map
    private static volatile Map<Locale, Locale> parentLocalesMap;
    static {
        parentLocalesMap = new ConcurrentHashMap<>();
        // Assuming these locales do NOT have irregular parent locales.
        parentLocalesMap.put(Locale.ROOT, Locale.ROOT);
        parentLocalesMap.put(Locale.ENGLISH, Locale.ENGLISH);
        parentLocalesMap.put(Locale.US, Locale.US);
    }

    public CLDRLocaleProviderAdapter() {
        LocaleDataMetaInfo nbmi = null;

        try {
            nbmi = AccessController.doPrivileged(new PrivilegedExceptionAction<LocaleDataMetaInfo>() {
                @Override
                public LocaleDataMetaInfo run() {
                    for (LocaleDataMetaInfo ldmi : ServiceLoader.loadInstalled(LocaleDataMetaInfo.class)) {
                        if (ldmi.getType() == LocaleProviderAdapter.Type.CLDR) {
                            return ldmi;
                        }
                    }
                    return null;
                }
            });
        }  catch (Exception e) {
            // Catch any exception, and continue as if only CLDR's base locales exist.
        }

        nonBaseMetaInfo = nbmi;
    }

    /**
     * Returns the type of this LocaleProviderAdapter
     * @return the type of this
     */
    @Override
    public LocaleProviderAdapter.Type getAdapterType() {
        return LocaleProviderAdapter.Type.CLDR;
    }

    @Override
    public BreakIteratorProvider getBreakIteratorProvider() {
        return null;
    }

    @Override
    public CollatorProvider getCollatorProvider() {
        return null;
    }

    @Override
    public Locale[] getAvailableLocales() {
        Set<String> all = createLanguageTagSet("AvailableLocales");
        Locale[] locs = new Locale[all.size()];
        int index = 0;
        for (String tag : all) {
            locs[index++] = Locale.forLanguageTag(tag);
        }
        return locs;
    }

    @Override
    protected Set<String> createLanguageTagSet(String category) {
        // Directly call Base tags, as we know it's in the base module.
        String supportedLocaleString = baseMetaInfo.availableLanguageTags(category);
        String nonBaseTags = null;

        if (nonBaseMetaInfo != null) {
            nonBaseTags = nonBaseMetaInfo.availableLanguageTags(category);
        }
        if (nonBaseTags != null) {
            if (supportedLocaleString != null) {
                supportedLocaleString += " " + nonBaseTags;
            } else {
                supportedLocaleString = nonBaseTags;
            }
        }
        if (supportedLocaleString == null) {
            return Collections.emptySet();
        }
        Set<String> tagset = new HashSet<>();
        StringTokenizer tokens = new StringTokenizer(supportedLocaleString);
        while (tokens.hasMoreTokens()) {
            tagset.add(tokens.nextToken());
        }
        return tagset;
    }

    // Implementation of ResourceBundleBasedAdapter
    @Override
    public List<Locale> getCandidateLocales(String baseName, Locale locale) {
        List<Locale> candidates = super.getCandidateLocales(baseName, locale);
        return applyParentLocales(baseName, candidates);
    }

    private List<Locale> applyParentLocales(String baseName, List<Locale> candidates) {
        // check irregular parents
        for (int i = 0; i < candidates.size(); i++) {
            Locale l = candidates.get(i);
            if (!l.equals(Locale.ROOT)) {
                Locale p = getParentLocale(l);
                if (p != null &&
                    !candidates.get(i+1).equals(p)) {
                    List<Locale> applied = candidates.subList(0, i+1);
                    applied.addAll(applyParentLocales(baseName, super.getCandidateLocales(baseName, p)));
                    return applied;
                }
            }
        }

        return candidates;
    }

    private static Locale getParentLocale(Locale locale) {
        Locale parent = parentLocalesMap.get(locale);

        if (parent == null) {
            String tag = locale.toLanguageTag();
            for (Map.Entry<Locale, String[]> entry : baseMetaInfo.parentLocales().entrySet()) {
                if (Arrays.binarySearch(entry.getValue(), tag) >= 0) {
                    parent = entry.getKey();
                    break;
                }
            }
            if (parent == null) {
                parent = locale; // non existent marker
            }
            parentLocalesMap.putIfAbsent(locale, parent);
        }

        if (locale.equals(parent)) {
            // means no irregular parent.
            parent = null;
        }

        return parent;
    }

    /**
     * This method returns equivalent CLDR supported locale for zh-HK,
     * no, no-NO locales so that COMPAT locales do not precede
     * those locales during ResourceBundle search path.
     */
    private static Locale getEquivalentLoc(Locale locale) {
        switch (locale.toString()) {
            case "zh_HK":
                return Locale.forLanguageTag("zh-Hant-HK");
            case "no":
            case "no_NO":
                return Locale.forLanguageTag("nb");
        }
        return locale;
    }

    @Override
    public boolean isSupportedProviderLocale(Locale locale, Set<String> langtags) {
        return Locale.ROOT.equals(locale)
                || langtags.contains(locale.stripExtensions().toLanguageTag())
                || langtags.contains(getEquivalentLoc(locale).toLanguageTag());
    }
}
