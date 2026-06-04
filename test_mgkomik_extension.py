import requests
from bs4 import BeautifulSoup

base_url = "https://id.mgkomik.cc"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Sec-Fetch-Site": "none",
    "Upgrade-Insecure-Requests": "1",
    "Referer": f"{base_url}/",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language": "en-US,en;q=0.9",
}

def test_page(path):
    url = f"{base_url}{path}"
    print(f"Testing URL: {url}")
    try:
        r = requests.get(url, headers=headers, timeout=15)
        print(f"Status: {r.status_code}")
        if r.status_code == 200:
            soup = BeautifulSoup(r.text, 'html.parser')
            # Check for manga elements based on popularMangaFromElement
            manga_elements = soup.select("div.item-thumb a")
            print(f"Found {len(manga_elements)} manga elements")
            for i, el in enumerate(manga_elements[:5]):
                title = el.get('title')
                href = el.get('href')
                print(f"  {i+1}. {title} ({href})")
        elif r.status_code == 403:
            if "cf-mitigated" in r.headers:
                print("Blocked by Cloudflare (Challenge)")
            else:
                print("Blocked (403) - likely Cloudflare")
    except Exception as e:
        print(f"Error: {e}")
    print("-" * 30)

# Madara usually has /manga/ or /komik/ (overridden in extension)
test_page("/")
test_page("/komik/")
test_page("/komik/?m_order=views") # Popular sort in Madara
test_page("/komik/?m_order=latest") # Latest sort in Madara
