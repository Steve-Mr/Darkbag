import urllib.request
import json
import re
import sys

def get_pr_review_comments(repo, pr_number):
    url = f"https://api.github.com/repos/{repo}/pulls/{pr_number}/comments"
    req = urllib.request.Request(url)
    try:
        with urllib.request.urlopen(req) as response:
            data = json.loads(response.read().decode('utf-8'))
            for comment in data:
                print(f"File: {comment['path']}")
                print(f"Line: {comment.get('line')}")
                print(f"Body: {comment['body']}")
                print("---")
    except Exception as e:
        print(f"Error fetching comments: {e}")

repo = "Steve-Mr/Darkbag"
pr_number = 221
get_pr_review_comments(repo, pr_number)
