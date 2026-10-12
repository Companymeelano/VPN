<?php
/**
 * Country / flag resolution.
 *
 * The app renders its OWN flag asset from the 2-letter `cc`, so the only job here is
 * "which country is this node in" — never "what is the vendor's marketing name".
 */
final class Country
{
    /** English / latin names, cities and datacenter names -> ISO2 */
    private static $map = [
        'ir' => 'IR', 'iran' => 'IR', 'tehran' => 'IR', 'paris' => 'FR', 'france' => 'FR',
        'de' => 'DE', 'germany' => 'DE', 'deutschland' => 'DE', 'frankfurt' => 'DE', 'nuremberg' => 'DE',
        'nuernberg' => 'DE', 'dusseldorf' => 'DE', 'munich' => 'DE', 'berlin' => 'DE',
        'us' => 'US', 'usa' => 'US', 'unitedstates' => 'US', 'america' => 'US', 'newyork' => 'US',
        'ashburn' => 'US', 'chicago' => 'US', 'miami' => 'US', 'dallas' => 'US', 'seattle' => 'US',
        'losangeles' => 'US', 'sanjose' => 'US', 'atlanta' => 'US', 'washington' => 'US', 'buffalo' => 'US',
        'nl' => 'NL', 'netherlands' => 'NL', 'holland' => 'NL', 'amsterdam' => 'NL',
        'gb' => 'GB', 'uk' => 'GB', 'unitedkingdom' => 'GB', 'london' => 'GB', 'england' => 'GB',
        'fr' => 'FR',
        'ca' => 'CA', 'canada' => 'CA', 'toronto' => 'CA', 'montreal' => 'CA', 'vancouver' => 'CA',
        'tr' => 'TR', 'turkey' => 'TR', 'turkiye' => 'TR', 'istanbul' => 'TR', 'ankara' => 'TR',
        'ae' => 'AE', 'uae' => 'AE', 'dubai' => 'AE', 'abudhabi' => 'AE',
        'fi' => 'FI', 'finland' => 'FI', 'helsinki' => 'FI',
        'se' => 'SE', 'sweden' => 'SE', 'stockholm' => 'SE',
        'no' => 'NO', 'norway' => 'NO', 'oslo' => 'NO',
        'dk' => 'DK', 'denmark' => 'DK', 'copenhagen' => 'DK',
        'pl' => 'PL', 'poland' => 'PL', 'warsaw' => 'PL',
        'ro' => 'RO', 'romania' => 'RO', 'bucharest' => 'RO', 'bucuresti' => 'RO',
        'hu' => 'HU', 'hungary' => 'HU', 'budapest' => 'HU',
        'bg' => 'BG', 'bulgaria' => 'BG', 'sofia' => 'BG',
        'pt' => 'PT', 'portugal' => 'PT', 'lisbon' => 'PT', 'lisboa' => 'PT',
        'es' => 'ES', 'spain' => 'ES', 'madrid' => 'ES', 'barcelona' => 'ES', 'valencia' => 'ES',
        'it' => 'IT', 'italy' => 'IT', 'milan' => 'IT', 'milano' => 'IT', 'rome' => 'IT', 'roma' => 'IT',
        'at' => 'AT', 'austria' => 'AT', 'vienna' => 'AT', 'wien' => 'AT',
        'ch' => 'CH', 'switzerland' => 'CH', 'zurich' => 'CH', 'zhurich' => 'CH', 'geneva' => 'CH',
        'be' => 'BE', 'belgium' => 'BE', 'brussels' => 'BE',
        'ie' => 'IE', 'ireland' => 'IE', 'dublin' => 'IE',
        'cz' => 'CZ', 'czech' => 'CZ', 'prague' => 'CZ', 'czechia' => 'CZ',
        'gr' => 'GR', 'greece' => 'GR', 'athens' => 'GR',
        'rs' => 'RS', 'serbia' => 'RS', 'belgrade' => 'RS',
        'hr' => 'HR', 'croatia' => 'HR', 'zagreb' => 'HR',
        'si' => 'SI', 'slovenia' => 'SI',
        'sk' => 'SK', 'slovakia' => 'SK', 'bratislava' => 'SK',
        'lt' => 'LT', 'lithuania' => 'LT', 'vilnius' => 'LT',
        'lv' => 'LV', 'latvia' => 'LV', 'riga' => 'LV',
        'ee' => 'EE', 'estonia' => 'EE', 'tallinn' => 'EE',
        'ua' => 'UA', 'ukraine' => 'UA', 'kyiv' => 'UA', 'kiev' => 'UA', 'odessa' => 'UA',
        'ru' => 'RU', 'russia' => 'RU', 'moscow' => 'RU', 'moskva' => 'RU', 'saintpetersburg' => 'RU',
        'md' => 'MD', 'moldova' => 'MD', 'chisinau' => 'MD',
        'ge' => 'GE', 'georgia' => 'GE', 'tbilisi' => 'GE',
        'am' => 'AM', 'armenia' => 'AM', 'yerevan' => 'AM',
        'az' => 'AZ', 'azerbaijan' => 'AZ', 'baku' => 'AZ',
        'kz' => 'KZ', 'kazakhstan' => 'KZ', 'almaty' => 'KZ',
        'uz' => 'UZ', 'uzbekistan' => 'UZ', 'tashkent' => 'UZ',
        'il' => 'IL', 'israel' => 'IL', 'telaviv' => 'IL',
        'qa' => 'QA', 'qatar' => 'QA', 'doha' => 'QA',
        'kw' => 'KW', 'kuwait' => 'KW',
        'bh' => 'BH', 'bahrain' => 'BH',
        'om' => 'OM', 'oman' => 'OM', 'muscat' => 'OM',
        'sa' => 'SA', 'saudiarabia' => 'SA', 'riyadh' => 'SA', 'jeddah' => 'SA',
        'jo' => 'JO', 'jordan' => 'JO', 'amman' => 'JO',
        'eg' => 'EG', 'egypt' => 'EG', 'cairo' => 'EG',
        'za' => 'ZA', 'southafrica' => 'ZA', 'capetown' => 'ZA', 'johannesburg' => 'ZA',
        'ng' => 'NG', 'nigeria' => 'NG', 'lagos' => 'NG',
        'in' => 'IN', 'india' => 'IN', 'mumbai' => 'IN', 'bombay' => 'IN', 'delhi' => 'IN', 'chennai' => 'IN',
        'pk' => 'PK', 'pakistan' => 'PK', 'lahore' => 'PK', 'karachi' => 'PK',
        'bd' => 'BD', 'bangladesh' => 'BD', 'dhaka' => 'BD',
        'jp' => 'JP', 'japan' => 'JP', 'tokyo' => 'JP', 'osaka' => 'JP',
        'kr' => 'KR', 'korea' => 'KR', 'seoul' => 'KR',
        'cn' => 'CN', 'china' => 'CN', 'beijing' => 'CN', 'shanghai' => 'CN',
        'hk' => 'HK', 'hongkong' => 'HK',
        'tw' => 'TW', 'taiwan' => 'TW', 'taipei' => 'TW',
        'sg' => 'SG', 'singapore' => 'SG',
        'my' => 'MY', 'malaysia' => 'MY', 'kualalumpur' => 'MY',
        'th' => 'TH', 'thailand' => 'TH', 'bangkok' => 'TH',
        'id' => 'ID', 'indonesia' => 'ID', 'jakarta' => 'ID',
        'ph' => 'PH', 'philippines' => 'PH', 'manila' => 'PH',
        'vn' => 'VN', 'vietnam' => 'VN', 'hanoi' => 'VN', 'hochiminh' => 'VN',
        'au' => 'AU', 'australia' => 'AU', 'sydney' => 'AU', 'melbourne' => 'AU', 'perth' => 'AU',
        'nz' => 'NZ', 'newzealand' => 'NZ', 'auckland' => 'NZ',
        'br' => 'BR', 'brazil' => 'BR', 'saopaulo' => 'BR', 'sao_paulo' => 'BR', 'riodejaneiro' => 'BR',
        'mx' => 'MX', 'mexico' => 'MX',
        'ar' => 'AR', 'argentina' => 'AR', 'buenosaires' => 'AR',
        'cl' => 'CL', 'chile' => 'CL', 'santiago' => 'CL',
        'pa' => 'PA', 'panama' => 'PA',
        'is' => 'IS', 'iceland' => 'IS', 'reykjavik' => 'IS',
        'lu' => 'LU', 'luxembourg' => 'LU',
        'mt' => 'MT', 'malta' => 'MT',
        'cy' => 'CY', 'cyprus' => 'CY', 'limassol' => 'CY',
    ];

    /** Persian names -> ISO2 (the raw VIP lists are full of these) */
    private static $fa = [
        'ایران' => 'IR', 'آلمان' => 'DE', 'امریکا' => 'US', 'امریکا متحده' => 'US', 'آمریکا' => 'US',
        'هلند' => 'NL', 'انگلستان' => 'GB', 'بریتانیا' => 'GB', 'فرانسه' => 'FR', 'ترکیه' => 'TR',
        'امارات' => 'AE', 'فنلاند' => 'FI', 'سوئد' => 'SE', 'سوئدن' => 'SE', 'نروژ' => 'NO',
        'کانادا' => 'CA', 'اتریش' => 'AT', 'بلژیک' => 'BE', 'لهستان' => 'PL', 'رومانی' => 'RO',
        'اوکراین' => 'UA', 'سنگاپور' => 'SG', 'ژاپن' => 'JP', 'کره' => 'KR', 'هند' => 'IN',
        'برزیل' => 'BR', 'روسیه' => 'RU', 'چین' => 'CN', 'استرالیا' => 'AU', 'ایتالیا' => 'IT',
        'اسپانیا' => 'ES', 'پرتغال' => 'PT', 'مجارستان' => 'HU', 'بلغارستان' => 'BG', 'یونان' => 'GR',
        'چک' => 'CZ', 'سوئیس' => 'CH', 'ایرلند' => 'IE', 'صربستان' => 'RS', 'کرواسی' => 'HR',
        'لتونی' => 'LV', 'لیتوانی' => 'LT', 'استونی' => 'EE', 'اسرائیل' => 'IL', 'قطر' => 'QA',
        'عربستان' => 'SA', 'کویت' => 'KW', 'بحرین' => 'BH', 'عمان' => 'OM', 'ارمنستان' => 'AM',
        'گرجستان' => 'GE', 'آذربایجان' => 'AZ', 'قزاقستان' => 'KZ', 'اندونزی' => 'ID', 'تایلند' => 'TH',
        'مالزی' => 'MY', 'ویتنام' => 'VN', 'مکزیک' => 'MX', 'آفریقای‌جنوبی' => 'ZA', 'آفریقای جنوبی' => 'ZA',
        'مصر' => 'EG', 'نایجریا' => 'NG', 'پاکستان' => 'PK', 'بنگلادش' => 'BD', 'نیوزیلند' => 'NZ',
    ];

    /** Emoji flag (regional indicators) -> ISO2 */
    public static function fromFlagEmoji($s)
    {
        if (!preg_match_all('/[\x{1F1E6}-\x{1F1FF}]{2}/u', (string) $s, $m)) {
            // UTF-8 fallback for old PCRE without unicode support
            if (preg_match_all('/(\xf0\x9f[\x87-\x87][\x80-\xbf])(\xf0\x9f[\x87-\x87][\x80-\xbf])/u', (string) $s, $m2)) {
                $m = $m2[0];
            } else {
                return null;
            }
        }
        $pairs = !empty($m[0]) ? $m[0] : $m;
        foreach ((array) $pairs as $flag) {
            $letters = '';
            $chars = function_exists('grapheme_str_split') ? grapheme_str_split($flag) : preg_split('//u', $flag, -1, PREG_SPLIT_NO_EMPTY);
            if (!is_array($chars) || count($chars) < 2) {
                continue;
            }
            foreach (array_slice($chars, 0, 2) as $c) {
                $cp = function_exists('mb_ord') ? mb_ord($c, 'UTF-8') : self::ordFallback($c);
                if ($cp === false || $cp < 0x1F1E6 || $cp > 0x1F1FF) {
                    continue 2;
                }
                $letters .= chr(ord('A') + ($cp - 0x1F1E6));
            }
            if (strlen($letters) === 2) {
                return $letters;
            }
        }
        return null;
    }

    private static function ordFallback($c)
    {
        $bytes = array_values(unpack('C*', $c));
        if (count($bytes) === 4) {
            return (($bytes[0] & 7) << 18) | (($bytes[1] & 63) << 12) | (($bytes[2] & 63) << 6) | ($bytes[3] & 63);
        }
        return false;
    }

    /** @return string|null ISO2 uppercase */
    public static function detect($remark, $host = '', $sni = '')
    {
        $cc = self::fromToken((string) $remark);
        if ($cc) {
            return $cc;
        }
        foreach ([(string) $sni, (string) $host] as $h) {
            if ($h === '' || filter_var($h, FILTER_VALIDATE_IP)) {
                continue;
            }
            // hostname labels: fr.example.com, node-de-01.example.net, helsinki2.example.com
            $labels = preg_split('~[.\-]~', strtolower($h));
            foreach ($labels as $lab) {
                if (strlen($lab) === 2 && preg_match('~^[a-z]{2}$~', $lab) && isset(self::$map[$lab])) {
                    return self::$map[$lab];
                }
            }
            foreach ($labels as $lab) {
                if (strlen($lab) <= 2) {
                    continue;
                }
                if (isset(self::$map[$lab])) {
                    return self::$map[$lab];
                }
                // helsinki2 / frankfurt-03 style labels
                foreach (self::$map as $needle => $iso) {
                    if (strlen($needle) > 3 && strpos($lab, $needle) === 0) {
                        return $iso;
                    }
                }
            }
        }
        return null;
    }

    /** @return string|null ISO2 uppercase, from free text */
    public static function fromToken($text)
    {
        $text = (string) $text;
        if ($text === '') {
            return null;
        }
        $flag = self::fromFlagEmoji($text);
        if ($flag) {
            return $flag;
        }
        $lc = self::normalize($text);
        // longest names first so "southafrica" wins over "africa"
        static $keys = null;
        if ($keys === null) {
            $keys = array_keys(self::$map);
            foreach (self::$fa as $faName => $_) {
                $keys[] = self::normalize($faName);
            }
            usort($keys, function ($a, $b) { return strlen($b) <=> strlen($a); });
        }
        foreach ($keys as $k) {
            if ($k === '' || strlen($k) < 2) {
                continue;
            }
            if (strlen($k) === 2) {
                if (preg_match('~(?:^|[^a-z0-9])' . preg_quote($k, '~') . '(?:$|[^a-z0-9])~', $lc)) {
                    $cc = $k === 'uk' ? 'GB' : strtoupper($k);
                    if (isset(self::$map[$k])) {
                        $cc = self::$map[$k];
                    }
                    return self::known($cc) ? $cc : null;
                }
                continue;
            }
            if (strpos($lc, $k) !== false) {
                $name = null;
                foreach (self::$map as $needle => $iso) {
                    if ($needle === $k) { $name = $iso; break; }
                }
                if ($name === null) {
                    foreach (self::$fa as $needle => $iso) {
                        if (self::normalize($needle) === $k) { $name = $iso; break; }
                    }
                }
                if ($name && self::known($name)) {
                    return $name;
                }
            }
        }
        return null;
    }

    private static function normalize($s)
    {
        $s = (string) $s;
        $s = function_exists('mb_strtolower') ? mb_strtolower($s, 'UTF-8') : strtolower($s);
        $s = preg_replace('/[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}\x{FE0F}]/u', ' ', $s);
        $s = str_replace(['‌', '‍'], ' ', $s); // ZWNJ/ZWJ so "آفریقای‌جنوبی" splits
        $s = preg_replace('~[^a-z0-9\x{0600}-\x{06FF}]+~u', '', $s);
        return (string) $s;
    }

    private static function known($cc)
    {
        return (bool) preg_match('~^[A-Z]{2}$~', (string) $cc);
    }

    /** Display name used only inside admin/debug output. */
    public static function nameFa($cc)
    {
        static $names = [
            'IR' => 'ایران', 'DE' => 'آلمان', 'US' => 'آمریکا', 'NL' => 'هلند', 'GB' => 'انگلستان',
            'FR' => 'فرانسه', 'TR' => 'ترکیه', 'AE' => 'امارات', 'FI' => 'فنلاند', 'SE' => 'سوئد',
            'CA' => 'کانادا', 'SG' => 'سنگاپور', 'JP' => 'ژاپن', 'IN' => 'هند', 'AU' => 'استرالیا',
            'IT' => 'ایتالیا', 'ES' => 'اسپانیا', 'RU' => 'روسیه', 'CN' => 'چین', 'HK' => 'هنگ‌کنگ',
            'UA' => 'اوکراین', 'PL' => 'لهستان', 'RO' => 'رومانی', 'AT' => 'اتریش', 'CH' => 'سوئیس',
            'BE' => 'بلژیک', 'NO' => 'نروژ', 'DK' => 'دانمارک', 'PT' => 'پرتغال', 'GR' => 'یونان',
        ];
        $cc = strtoupper((string) $cc);
        return isset($names[$cc]) ? $names[$cc] : '';
    }

    /** Regional-indicator emoji — only for admin previews, the app draws its own assets. */
    public static function flagEmoji($cc)
    {
        $cc = strtoupper(preg_replace('~[^A-Za-z]~', '', (string) $cc));
        if (strlen($cc) !== 2) {
            return '🏳️';
        }
        $s = '';
        foreach (str_split($cc) as $ch) {
            $cp = 0x1F1E6 + (ord($ch) - ord('A'));
            $s .= self::utf8($cp);
        }
        return $s;
    }

    private static function utf8($cp)
    {
        if (function_exists('mb_chr')) {
            return mb_chr($cp, 'UTF-8');
        }
        $out = '';
        if ($cp < 0x80) { return chr($cp); }
        if ($cp < 0x800) { $out = chr(0xC0 | ($cp >> 6)) . chr(0x80 | ($cp & 63)); return $out; }
        if ($cp < 0x10000) { return chr(0xE0 | ($cp >> 12)) . chr(0x80 | (($cp >> 6) & 63)) . chr(0x80 | ($cp & 63)); }
        return chr(0xF0 | ($cp >> 18)) . chr(0x80 | (($cp >> 12) & 63)) . chr(0x80 | (($cp >> 6) & 63)) . chr(0x80 | ($cp & 63));
    }
}
