// Host test harness for the AndSlice engine. It drives the exact code the
// Android app uses, so slicing can be verified on a desktop or in CI:
//
//   andslice_cli --bundle Kobra3.ini [--printer P] [--print P] [--filament F]
//                [--set key=value]... -o out.gcode model.stl [model2.stl ...]
//   andslice_cli --bundle Kobra3.ini --list [--printer P]
#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include <boost/filesystem.hpp>
#include <nlohmann/json.hpp>

#include "Engine.hpp"

using andslice::Engine;
using nlohmann::json;

static int usage()
{
    std::cerr << "usage: andslice_cli --bundle FILE [--printer NAME] [--print NAME] [--filament NAME]\n"
                 "                    [--set KEY=VALUE]... (--list | -o OUT.gcode MODEL...)\n";
    return 2;
}

int main(int argc, char** argv)
{
    std::string bundle, printer, print, filament, output;
    std::vector<std::pair<std::string, std::string>> overrides;
    std::vector<std::string> models;
    bool list = false;

    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : std::string(); };
        if (a == "--bundle")        bundle = next();
        else if (a == "--printer")  printer = next();
        else if (a == "--print")    print = next();
        else if (a == "--filament") filament = next();
        else if (a == "-o")         output = next();
        else if (a == "--list")     list = true;
        else if (a == "--set") {
            const std::string kv = next();
            const size_t eq = kv.find('=');
            if (eq == std::string::npos)
                return usage();
            overrides.emplace_back(kv.substr(0, eq), kv.substr(eq + 1));
        } else if (!a.empty() && a[0] == '-')
            return usage();
        else
            models.push_back(a);
    }
    if (bundle.empty() || (!list && (output.empty() || models.empty())))
        return usage();

    try {
        const auto data_dir = boost::filesystem::temp_directory_path() / "andslice-cli";
        Engine& engine = Engine::get();
        engine.init(data_dir.string(), data_dir.string(), bundle);

        const json presets = json::parse(engine.presets_json(printer));
        if (printer.empty())  printer  = presets["printer"];
        if (print.empty())    print    = presets["default_print"];
        if (filament.empty()) filament = presets["default_filament"];

        if (list) {
            std::cout << presets.dump(2) << "\n";
            const json defs = json::parse(engine.option_defs_json());
            std::cout << "option defs: print " << defs["print"].size() << ", filament " << defs["filament"].size()
                      << ", printer " << defs["printer"].size() << "\n";
            return 0;
        }

        engine.set_printer(printer);
        // Leave room for brims when placing several objects, as the app does.
        for (const auto& [key, value] : overrides)
            if (key == "brim_width")
                engine.set_arrange_distance(6.f + 2.f * std::stof(value));
        for (const std::string& m : models)
            std::cout << "loaded " << m << ": " << engine.add_model(m, "") << "\n";
        std::cout << "plate: " << engine.objects_json() << "\n";

        andslice::SliceRequest req{ printer, print, filament, overrides, output };
        const std::string result = engine.slice(req, [](int percent, const std::string& msg) {
            std::cout << percent << "% " << msg << "\n";
        });
        json r = json::parse(result);
        if (r.contains("layers"))
            r["layers"] = r["layers"].size(); // keep the output short
        std::cout << r.dump(2) << "\n";
        return r.value("ok", false) ? 0 : 1;
    } catch (const std::exception& ex) {
        std::cerr << "error: " << ex.what() << "\n";
        return 1;
    }
}
