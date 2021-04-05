import json
import sys

def main(inFile, outFile):
    out = []
    with open(infile) as inF:
        manifest = json.load(inF)
        files = manifest["files"]
        for file in files:
            out.append({
                'source': 'curse',
                'projectID': file["projectID"],
                'fileID': file["fileID"]
            })

    with open(outfile, "w") as outF:
        outObj = {
            'mods': out
        }
        json.dump(outObj, outF, indent=2, sort_keys=True)

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: curseconverter.py INFILE OUTFILE")
        sys.exit(1)
    infile = sys.argv[1]
    outfile = sys.argv[2]
    main(infile, outfile)
